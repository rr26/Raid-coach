package com.raidcoach.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val DUNGEON_NAMES = listOf("Clan Boss", "Hydra", "Fire Knight", "Dragon", "Spider", "Ice Golem")

class AccountActivity : AppCompatActivity() {

    private class DungeonRow(val dungeon: String, val highest: EditText, val farmable: EditText, val avgTime: EditText)
    private class TeamRow(val topic: String, val notes: EditText)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: ChatDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = ChatDatabase.getInstance(this)

        scope.launch {
            val accountMeta = withContext(Dispatchers.IO) { database.accountMetaDao().get() }
            val dungeonProgress = withContext(Dispatchers.IO) {
                database.dungeonProgressDao().getAll().associateBy { it.dungeon }
            }
            val topics = withContext(Dispatchers.IO) {
                database.topicDao().getAll().sortedBy { it.sortOrder }.map { it.name }
            }
            val savedTeams = withContext(Dispatchers.IO) {
                database.savedTeamDao().getAll().associateBy { it.topic }
            }

            buildForm(accountMeta, dungeonProgress, topics, savedTeams)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildForm(
        accountMeta: AccountMetaEntity?,
        dungeonProgress: Map<String, DungeonProgressEntity>,
        topics: List<String>,
        savedTeams: Map<String, SavedTeamEntity>
    ) {
        val playerLevelInput = EditText(this).apply {
            hint = "Player level (e.g. Lvl 60)"
            setText(accountMeta?.playerLevel.orEmpty())
        }
        val focusAreasInput = EditText(this).apply {
            hint = "Current focus areas"
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(accountMeta?.focusAreas.orEmpty())
        }
        val notesInput = EditText(this).apply {
            hint = "Free-text notes"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(accountMeta?.notes.orEmpty())
        }

        val dungeonRows = DUNGEON_NAMES.map { dungeon ->
            val existing = dungeonProgress[dungeon]
            DungeonRow(
                dungeon = dungeon,
                highest = EditText(this).apply {
                    hint = "Highest stage beaten"
                    setText(existing?.highestStageBeaten.orEmpty())
                },
                farmable = EditText(this).apply {
                    hint = "Farmable stage"
                    setText(existing?.farmableStage.orEmpty())
                },
                avgTime = EditText(this).apply {
                    hint = "Avg run time"
                    setText(existing?.avgRunTime.orEmpty())
                }
            )
        }

        val teamRows = topics.map { topic ->
            TeamRow(
                topic = topic,
                notes = EditText(this).apply {
                    hint = "Champions + role notes for $topic"
                    minLines = 2
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setText(savedTeams[topic]?.teamNotes.orEmpty())
                }
            )
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val now = System.currentTimeMillis()
                val playerLevel = playerLevelInput.text.toString().trim()
                val focusAreas = focusAreasInput.text.toString().trim()
                val notes = notesInput.text.toString().trim()

                scope.launch(Dispatchers.IO) {
                    database.accountMetaDao().upsert(AccountMetaEntity(0, playerLevel, focusAreas, notes, now))

                    dungeonRows.forEach { row ->
                        val highest = row.highest.text.toString().trim()
                        val farmable = row.farmable.text.toString().trim()
                        val avgTime = row.avgTime.text.toString().trim()
                        if (highest.isNotEmpty() || farmable.isNotEmpty() || avgTime.isNotEmpty()) {
                            database.dungeonProgressDao().upsert(
                                DungeonProgressEntity(row.dungeon, highest, farmable, avgTime, now)
                            )
                        }
                    }

                    teamRows.forEach { row ->
                        val teamNotes = row.notes.text.toString().trim()
                        if (teamNotes.isNotEmpty()) {
                            database.savedTeamDao().upsert(SavedTeamEntity(row.topic, teamNotes, now))
                        }
                    }
                }
                finish()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))

            addView(sectionHeader("Account"))
            addView(labeled("Player level", playerLevelInput))
            addView(labeled("Focus areas", focusAreasInput))
            addView(labeled("Notes", notesInput))

            addView(sectionHeader("Dungeon progress"))
            dungeonRows.forEach { row ->
                addView(sectionHeader(row.dungeon, isSub = true))
                addView(labeled("Highest stage beaten", row.highest))
                addView(labeled("Farmable stage", row.farmable))
                addView(labeled("Avg run time", row.avgTime))
            }

            addView(sectionHeader("Saved teams"))
            if (teamRows.isEmpty()) {
                addView(TextView(context).apply { text = "No tabs yet — create one in the overlay first." })
            }
            teamRows.forEach { row ->
                addView(sectionHeader(row.topic, isSub = true))
                addView(row.notes)
            }

            addView(
                saveButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24) }
            )
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sectionHeader(text: String, isSub: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (isSub) 14f else 18f
        setPadding(0, dp(if (isSub) 12 else 20), 0, dp(4))
    }

    private fun labeled(label: String, input: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = label; textSize = 11f })
        addView(input)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
