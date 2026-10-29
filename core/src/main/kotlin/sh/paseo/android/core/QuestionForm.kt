package sh.paseo.android.core

import kotlinx.serialization.json.*

data class QuestionOption(val label: String, val description: String)
data class Question(val header: String, val text: String, val options: List<QuestionOption>,
    val multiSelect: Boolean, val allowOther: Boolean, val allowEmpty: Boolean, val placeholder: String) {
    val showsText: Boolean get() = options.isEmpty() || allowOther
    fun answered(selected: Set<Int>, other: String): Boolean = selected.any { it in options.indices } ||
        (showsText && (other.isNotBlank() || allowEmpty))
    fun answer(selected: Set<Int>, other: String): String {
        val labels = selected.filter { it in options.indices }.map { options[it].label }
        val custom = other.trim().takeIf { showsText && it.isNotEmpty() }
        return if (custom != null) { if (multiSelect) (labels + custom).joinToString(", ") else custom }
            else labels.joinToString(", ")
    }
}
fun PermissionRequest.questions(): List<Question> {
    val values = input?.get("questions") as? JsonArray ?: return emptyList()
    return values.map { element ->
        val q = element as? JsonObject ?: return emptyList()
        if (q["header"] !is JsonPrimitive || q["question"] !is JsonPrimitive || q["options"] !is JsonArray) return emptyList()
        val options = q.array("options").map { option ->
            val o = option as? JsonObject ?: return emptyList()
            if (o["label"] !is JsonPrimitive) return emptyList()
            QuestionOption(o.string("label"), o.string("description"))
        }
        Question(q.string("header"), q.string("question"), options, q.bool("multiSelect"), q.bool("allowOther") || q.bool("isOther"), q.bool("allowEmpty"), q.string("placeholder"))
    }
}
fun PermissionRequest.answeredInput(answers: Map<String, String>): JsonObject = JsonObject(input.orEmpty() +
    ("answers" to JsonObject(answers.mapValues { JsonPrimitive(it.value) })))
