package sh.paseo.android.core

/** Matches the official runtime → configured → default model selection rules. */
data class AgentModelSelection(
    val model: ModelOption?,
    val modelId: String?,
    val modelLabel: String,
    val thinking: ModeOption?,
) {
    val thinkingOptions: List<ModeOption> get() = model?.thinkingOptions.orEmpty()
}

fun resolveAgentModelSelection(agent: Agent, models: List<ModelOption>): AgentModelSelection {
    fun normalized(id: String?) = id?.trim()?.takeIf { it.isNotEmpty() }
    fun find(id: String?) = id?.let { key -> models.find { it.id == key } ?: models.find { key in it.aliases } }
    val runtimeId = normalized(agent.runtimeInfo?.string("model"))
    val preferredId = find(runtimeId)?.id ?: normalized(agent.model) ?: runtimeId
    // An unknown explicit model must not inherit a different model's thinking capabilities.
    val model = if (preferredId == null) models.find { it.isDefault } ?: models.firstOrNull() else find(preferredId)
    val options = model?.thinkingOptions.orEmpty()
    val runtimeThinking = options.find { it.id == agent.runtimeInfo?.string("thinkingOptionId") }
    val thinkingId = runtimeThinking?.id
        ?: agent.thinkingOptionId?.takeIf { it.isNotEmpty() && it != "default" }
        ?: model?.defaultThinkingOptionId
        ?: options.find { it.isDefault }?.id
    return AgentModelSelection(model, model?.id ?: preferredId, model?.displayName ?: preferredId ?: agent.provider,
        options.find { it.id == thinkingId } ?: options.firstOrNull())
}

internal fun requireAgentControlAccepted(result: kotlinx.serialization.json.JsonObject) {
    check(result.bool("accepted")) { result.string("error").ifBlank { "主机未接受此设置，请重试" } }
}
