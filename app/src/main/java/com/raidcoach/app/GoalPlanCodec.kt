package com.raidcoach.app

import org.json.JSONArray
import org.json.JSONObject

data class GoalStep(val text: String, val done: Boolean)

object GoalPlanCodec {

    private val LEADING_NUMBER_OR_BULLET = Regex("""^(\d+[.)]|[-*•])\s*""")

    fun encode(steps: List<GoalStep>): String {
        val array = JSONArray()
        steps.forEach { step ->
            array.put(JSONObject().put("text", step.text).put("done", step.done))
        }
        return array.toString()
    }

    fun decode(raw: String): List<GoalStep> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                GoalStep(obj.optString("text"), obj.optBoolean("done", false))
            }
        }.getOrDefault(emptyList())
    }

    // Best-effort split of the model's freeform numbered/bulleted plan reply into individual steps.
    fun parsePlanText(raw: String): List<GoalStep> {
        return raw.lines()
            .map { it.trim().replace(LEADING_NUMBER_OR_BULLET, "") }
            .filter { it.isNotEmpty() }
            .map { GoalStep(it, done = false) }
    }
}
