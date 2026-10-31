package sh.paseo.android.core

import org.junit.Assert.*
import org.junit.Test

class AgentControlsTest {
    private val low = ModeOption("low", "Low")
    private val high = ModeOption("high", "High", isDefault = true)
    private val supported = ModelOption("current-model", "Model", thinkingOptions = listOf(low, high), aliases = listOf("current"), isDefault = true)
    private val other = ModelOption("other", "Other")
    private val models = listOf(other, supported)
    private fun agent(model: String? = null, explicit: String? = null, runtime: String? = null, thinking: String? = null) =
        Agent("agent", model = model, thinkingOptionId = explicit, runtimeInfo = jsonObject("model" to runtime, "thinkingOptionId" to thinking))

    @Test fun defaultModelUsesAdvertisedDefaultEffortEvenWhenItIsNotFirst() {
        val selection = resolveAgentModelSelection(agent(), models)
        assertEquals(supported, selection.model)
        assertEquals(high, selection.thinking)
        assertEquals(low, resolveAgentModelSelection(agent(), listOf(supported.copy(defaultThinkingOptionId = "low"))).thinking)
    }
    @Test fun aliasesAndWhitespaceResolveCapabilities() {
        assertEquals(supported, resolveAgentModelSelection(agent(" current "), models).model)
        assertEquals(supported, resolveAgentModelSelection(agent("   "), models).model)
    }
    @Test fun runtimeModelAndSupportedRuntimeEffortWin() {
        val selection = resolveAgentModelSelection(agent("other", "high", "current", "low"), models)
        assertEquals("current-model", selection.modelId)
        assertEquals(low, selection.thinking)
    }
    @Test fun invalidRuntimeFallsBackToExplicitThenDefaultThenFirst() {
        assertEquals(low, resolveAgentModelSelection(agent("current", "low", "unknown", "unsupported"), models).thinking)
        assertEquals(high, resolveAgentModelSelection(agent("current", "default"), models).thinking)
        assertEquals(low, resolveAgentModelSelection(agent("current", "unsupported"), models).thinking)
        assertEquals(low, resolveAgentModelSelection(agent(), listOf(supported.copy(thinkingOptions = listOf(low)))).thinking)
    }
    @Test fun unknownExplicitModelDoesNotBorrowAnotherModelsThinking() {
        val selection = resolveAgentModelSelection(agent("unknown"), models)
        assertNull(selection.model)
        assertNull(selection.thinking)
        assertEquals("unknown", selection.modelLabel)
        assertTrue(selection.thinkingOptions.isEmpty())
    }
    @Test fun switchingToModelWithoutThinkingRemovesOptions() {
        assertNull(resolveAgentModelSelection(agent("other", "high"), models).thinking)
        assertEquals(supported, resolveAgentModelSelection(agent(), listOf(supported.copy(isDefault = false), other)).model?.copy(isDefault = true))
    }
    @Test fun wireSnapshotKeepsRuntimeAliasesAndDefaults() {
        val model = wireJson.decodeFromString<ModelOption>("""{"id":"actual","aliases":["alias"],"isDefault":true,"thinkingOptions":[{"id":"high","label":"High","isDefault":true}]}""")
        val agent = wireJson.decodeFromString<Agent>("""{"id":"agent","runtimeInfo":{"model":"alias","thinkingOptionId":"high"}}""")
        assertEquals("actual", resolveAgentModelSelection(agent, listOf(model)).modelId)
        assertEquals("high", resolveAgentModelSelection(agent, listOf(model)).thinking?.id)
    }
    @Test fun rejectedSettingPropagatesServerErrorInsteadOfPretendingToSucceed() {
        requireAgentControlAccepted(jsonObject("accepted" to true))
        val failure = runCatching { requireAgentControlAccepted(jsonObject("accepted" to false, "error" to "Unsupported effort")) }.exceptionOrNull()
        assertEquals("Unsupported effort", failure?.message)
    }
}
