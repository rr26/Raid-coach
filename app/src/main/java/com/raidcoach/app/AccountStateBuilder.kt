package com.raidcoach.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Builds the compact "ACCOUNT STATE" block prepended to every coach system prompt: dungeon
// progress, the current tab's saved team, account meta, that tab's active goals, and a bounded
// slice of the global champion research cache. Built fresh from Room on every request rather than
// stored as chat history, so it never compounds the conversation's token cost as turns pile up.
object AccountStateBuilder {

    private const val MAX_CHARS = 6000 // ~1500 tokens at a ~4 chars/token estimate
    private const val MAX_CHAMPION_ENTRIES = 15
    private const val MAX_CHAMPION_SUMMARY_CHARS = 90

    // topic == null builds a cross-tab view (used for the "what should I do today" request):
    // no single tab's saved team, and goals cover every tab instead of just one.
    suspend fun build(database: ChatDatabase, topic: String?): String = withContext(Dispatchers.IO) {
        val accountMeta = database.accountMetaDao().get()
        val dungeonProgress = database.dungeonProgressDao().getAll()
        val savedTeam = topic?.let { database.savedTeamDao().get(it) }
        val goals = if (topic != null) {
            database.goalDao().getByTopic(topic).filter { it.status == GoalStatus.ACTIVE }
        } else {
            database.goalDao().getAllActive()
        }
        val championCache = database.championCacheDao().getAll().sortedByDescending { it.timestamp }

        val sections = mutableListOf<String>()

        if (accountMeta != null && (accountMeta.playerLevel.isNotBlank() || accountMeta.focusAreas.isNotBlank() || accountMeta.notes.isNotBlank())) {
            sections += buildString {
                appendLine("Account (updated ${ageLabel(accountMeta.lastUpdated)}):")
                if (accountMeta.playerLevel.isNotBlank()) appendLine("- Level: ${accountMeta.playerLevel}")
                if (accountMeta.focusAreas.isNotBlank()) appendLine("- Focus: ${accountMeta.focusAreas}")
                if (accountMeta.notes.isNotBlank()) appendLine("- Notes: ${accountMeta.notes}")
            }.trim()
        }

        if (dungeonProgress.isNotEmpty()) {
            sections += buildString {
                appendLine("Dungeon progress:")
                dungeonProgress.forEach { entry ->
                    appendLine(
                        "- ${entry.dungeon}: highest ${entry.highestStageBeaten.ifBlank { "?" }}, " +
                            "farming ${entry.farmableStage.ifBlank { "?" }}, " +
                            "~${entry.avgRunTime.ifBlank { "?" }} per run (updated ${ageLabel(entry.lastUpdated)})"
                    )
                }
            }.trim()
        }

        if (savedTeam != null && savedTeam.teamNotes.isNotBlank()) {
            sections += "Saved team for $topic (updated ${ageLabel(savedTeam.lastUpdated)}):\n${savedTeam.teamNotes.trim()}"
        }

        if (goals.isNotEmpty()) {
            sections += buildString {
                appendLine(if (topic != null) "Active goals for $topic:" else "Active goals (all tabs):")
                goals.forEach { goal ->
                    val steps = GoalPlanCodec.decode(goal.plan)
                    val label = if (topic != null) "\"${goal.title}\"" else "\"${goal.title}\" [${goal.topic}]"
                    val progress = if (steps.isEmpty()) "no plan yet" else "${steps.count { it.done }}/${steps.size} steps done"
                    appendLine("- $label ($progress)")
                    steps.forEach { step -> appendLine("  [${if (step.done) "x" else " "}] ${step.text}") }
                }
            }.trim()
        }

        if (championCache.isNotEmpty()) {
            sections += buildString {
                val slice = championCache.take(MAX_CHAMPION_ENTRIES)
                appendLine("Champion research cache (${slice.size} most recent):")
                slice.forEach { entry ->
                    appendLine("- ${entry.championName}: ${entry.summary.take(MAX_CHAMPION_SUMMARY_CHARS)}")
                }
            }.trim()
        }

        fitToBudget(sections)
    }

    // Trims lowest-priority sections first (champion cache is appended last, so it's the first
    // dropped) until the block fits the token budget; goals/team/dungeon progress are kept intact
    // whenever possible since they're the most decision-relevant for the coach.
    private fun fitToBudget(sections: List<String>): String {
        val remaining = sections.toMutableList()
        var block = render(remaining)
        while (block.length > MAX_CHARS && remaining.size > 1) {
            remaining.removeAt(remaining.size - 1)
            block = render(remaining)
        }
        return block.take(MAX_CHARS)
    }

    private fun render(sections: List<String>): String =
        if (sections.isEmpty()) "ACCOUNT STATE: (not set up yet)" else "ACCOUNT STATE:\n" + sections.joinToString("\n\n")

    private fun ageLabel(timestamp: Long): String {
        val days = (System.currentTimeMillis() - timestamp) / (24 * 60 * 60 * 1000)
        return when {
            days <= 0 -> "today"
            days == 1L -> "1 day ago"
            else -> "$days days ago"
        }
    }
}
