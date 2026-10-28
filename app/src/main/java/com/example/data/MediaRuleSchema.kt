package com.example.data

import org.json.JSONObject

sealed interface MediaPredicate {
    data object ExactDuplicate : MediaPredicate
    data class Obscured(val threshold: Double) : MediaPredicate
}

sealed interface MediaAction {
    data object MoveToBin : MediaAction
    data class AddTag(val name: String) : MediaAction
}

data class MediaRuleDefinition(val predicate: MediaPredicate, val actions: List<MediaAction>)

object MediaRuleSchema {
    private val rootKeys = setOf("predicate", "actions")
    private val predicateKeys = setOf("type", "threshold")
    private val actionKeys = setOf("type", "name")

    fun parse(raw: String): Result<MediaRuleDefinition> = runCatching {
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == rootKeys) { "Rule fields must be exactly predicate and actions" }
        val predicateJson = root.getJSONObject("predicate")
        require(predicateJson.keys().asSequence().all { it in predicateKeys }) { "Unknown predicate field" }
        val predicate = when (predicateJson.getString("type")) {
            "exact_duplicate" -> {
                require(predicateJson.keys().asSequence().toSet() == setOf("type")) { "Duplicate predicate accepts no parameters" }
                MediaPredicate.ExactDuplicate
            }
            "obscured" -> {
                require(predicateJson.keys().asSequence().toSet() == setOf("type", "threshold")) { "Obscured requires threshold" }
                val threshold = predicateJson.getDouble("threshold")
                require(threshold in 0.0..1.0) { "Threshold must be between 0 and 1" }
                MediaPredicate.Obscured(threshold)
            }
            else -> error("Unknown predicate")
        }
        val actionsJson = root.getJSONArray("actions")
        require(actionsJson.length() in 1..2) { "One or two actions required" }
        val actions = (0 until actionsJson.length()).map { index ->
            val item = actionsJson.getJSONObject(index)
            require(item.keys().asSequence().all { it in actionKeys }) { "Unknown action field" }
            when (item.getString("type")) {
                "move_to_bin" -> {
                    require(item.keys().asSequence().toSet() == setOf("type")) { "Move action accepts no parameters" }
                    MediaAction.MoveToBin
                }
                "add_tag" -> {
                    require(item.keys().asSequence().toSet() == setOf("type", "name")) { "Tag action requires name" }
                    val name = item.getString("name").trim()
                    require(name.isNotEmpty() && name.length <= 40) { "Tag name must be 1 to 40 characters" }
                    MediaAction.AddTag(name)
                }
                else -> error("Unknown action")
            }
        }
        require(actions.distinct().size == actions.size) { "Duplicate actions are not allowed" }
        MediaRuleDefinition(predicate, actions)
    }

    fun duplicateJson(): String = """{"predicate":{"type":"exact_duplicate"},"actions":[{"type":"move_to_bin"}]}"""
    fun obscuredJson(threshold: Double): String = JSONObject()
        .put("predicate", JSONObject().put("type", "obscured").put("threshold", threshold))
        .put("actions", org.json.JSONArray().put(JSONObject().put("type", "add_tag").put("name", "Obscured")).put(JSONObject().put("type", "move_to_bin")))
        .toString()
}

