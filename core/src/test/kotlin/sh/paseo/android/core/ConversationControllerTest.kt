@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package sh.paseo.android.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class ConversationControllerTest {
    private val host = Host(id = "host", name = "Test", url = "ws://localhost")
    private val key = ConversationKey(host.id, "a")
    private fun snapshot(running: Boolean = false, permissions: List<PermissionRequest> = emptyList()) = PaseoState(host = host,
        connection = ConnectionStatus.Connected, agents = listOf(Agent("a", status = if (running) "running" else "idle", activeTurnId = "t", pendingPermissions = permissions)), selectedAgentId = "a")
    @Test fun clearsImmediatelyAndMergesFailedDraftWithNewTypingAndReusesId() = runTest {
        val receipt = CompletableDeferred<String>()
        val sent = mutableListOf<OutgoingMessage>()
        val controller = ConversationController(this, { _, message, _ -> sent += message; if (sent.size == 1) receipt.await() else "accepted" }, {})
        controller.update(snapshot())
        controller.setDraft(key, "  first  ")
        controller.send(key)
        assertEquals("", controller.session(key).draft.text)
        assertTrue(controller.session(key).sending)
        runCurrent()
        controller.setDraft(key, "second")
        receipt.completeExceptionally(IOException("rejected")); runCurrent()
        assertEquals("first\n\nsecond", controller.session(key).draft.text)
        assertTrue(controller.session(key).optimistic.isEmpty())
        controller.retry(key); runCurrent()
        assertEquals(sent[0].id, sent[1].id)
        assertEquals("second", controller.session(key).draft.text)
        assertEquals("first", sent[1].draft.text)
    }
    @Test fun defaultsToSteerAndAlternateQueuesThenDrainsOneMessagePerCompletedTurn() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val controller = ConversationController(this, { _, message, behavior -> sent += message.draft.text to behavior; "accepted" }, {})
        controller.update(snapshot(true))
        controller.setDraft(key, "steer"); controller.send(key); runCurrent()
        assertEquals("steer", sent.single().second)
        assertEquals("t", controller.session(key).optimistic.single().turnId)
        for (text in listOf("one", "two")) { controller.setDraft(key, text); controller.send(key, alternate = true) }
        assertEquals(listOf("one", "two"), controller.session(key).queue.map { it.draft.text })
        controller.update(snapshot(false)); runCurrent()
        assertEquals(listOf("steer", "one"), sent.map { it.first })
        controller.update(snapshot(false)); runCurrent()
        assertEquals(2, sent.size)
        controller.update(snapshot(true)); controller.update(snapshot(false)); runCurrent()
        assertEquals(listOf("steer", "one", "two"), sent.map { it.first })
        assertTrue(controller.session(key).queue.isEmpty())
    }
    @Test fun pendingPermissionMakesDefaultQueueInterruptAndAlternateStillQueues() = runTest {
        val behaviors = mutableListOf<String>()
        val controller = ConversationController(this, { _, _, behavior -> behaviors += behavior; "accepted" }, {})
        controller.behavior = SendBehavior.Queue
        controller.update(snapshot(true, listOf(PermissionRequest("p"))))
        controller.setDraft(key, "answer"); controller.send(key); runCurrent()
        assertEquals(listOf("interrupt"), behaviors)
        controller.setDraft(key, "later"); controller.send(key, alternate = true)
        assertEquals("later", controller.session(key).queue.single().draft.text)
    }
    @Test fun forceQueueAlwaysQueuesWhileAgentIsRunning() = runTest {
        val controller = ConversationController(this, { _, _, _ -> "accepted" }, {})
        controller.behavior = SendBehavior.Queue
        controller.update(snapshot(true, listOf(PermissionRequest("p"))))
        controller.setDraft(key, "later"); controller.send(key, forceQueue = true)
        assertEquals("later", controller.session(key).queue.single().draft.text)
    }
    @Test fun editingQueueRestoresAttachmentsAndPreservesUnsentDraft() = runTest {
        val controller = ConversationController(this, { _, _, _ -> "accepted" }, {})
        controller.update(snapshot(true))
        val file = ChatAttachment(name = "Main.kt", payload = jsonObject("type" to "text", "text" to "Workspace file: Main.kt"))
        controller.attach(key, listOf(file)); controller.setDraft(key, "queued"); controller.send(key, true)
        val id = controller.session(key).queue.single().id
        controller.setDraft(key, "new"); controller.editQueued(key, id)
        assertEquals("queued\n\nnew", controller.session(key).draft.text)
        assertEquals(listOf(file), controller.session(key).draft.attachments)
        assertTrue(controller.session(key).queue.isEmpty())
    }
    @Test fun failedQueueDrainReturnsToFrontAndDoesNotBusyLoop() = runTest {
        var attempts = 0
        val controller = ConversationController(this, { _, _, _ -> attempts++; throw IOException("offline") }, {})
        controller.update(snapshot(true))
        for (text in listOf("one", "two")) { controller.setDraft(key, text); controller.send(key, true) }
        controller.update(snapshot(false)); runCurrent()
        assertEquals(1, attempts)
        assertEquals(listOf("one", "two"), controller.session(key).queue.map { it.draft.text })
        controller.update(snapshot(false)); runCurrent()
        assertEquals(1, attempts)
        assertEquals("offline", controller.session(key).error)
    }
    @Test fun canonicalEchoWinsOverLostReceiptAndDoesNotDuplicate() = runTest {
        val receipt = CompletableDeferred<String>()
        val controller = ConversationController(this, { _, _, _ -> receipt.await() }, {})
        controller.update(snapshot())
        controller.setDraft(key, "sent"); controller.send(key); runCurrent()
        val id = controller.session(key).optimistic.single().id
        controller.update(snapshot().copy(timeline = listOf(TimelineRow("canonical", "user_message", "sent", messageId = id))))
        receipt.completeExceptionally(IOException("lost receipt")); runCurrent()
        assertTrue(controller.session(key).optimistic.isEmpty())
        assertTrue(controller.session(key).draft.empty)
        assertNull(controller.session(key).error)
    }
    @Test fun hostSwitchAndIndependentAgentsCannotMixReceiptsOrBlockEditing() = runTest {
        val receipt = CompletableDeferred<String>()
        val controller = ConversationController(this, { _, _, _ -> receipt.await() }, {})
        controller.update(snapshot())
        controller.setDraft(key, "host one"); controller.send(key); runCurrent()
        val other = ConversationKey("other-host", "a")
        controller.update(snapshot().copy(host = host.copy(id = other.hostId)))
        controller.setDraft(other, "host two")
        receipt.completeExceptionally(IOException("switched")); runCurrent()
        assertEquals("host one", controller.session(key).draft.text)
        assertEquals("host two", controller.session(other).draft.text)
        assertFalse(controller.session(other).sending)
    }
    @Test fun restoresUnconfirmedOutboxAndRetriesTheOriginalIdAfterProcessDeath() = runTest {
        val original = OutgoingMessage(id = "saved-id", draft = ChatDraft("pending"))
        val persisted = ConversationMemory(pending = listOf(original))
        val memory = wireJson.decodeFromString<ConversationMemory>(wireJson.encodeToString(ConversationMemory.serializer(), persisted))
        val ids = mutableListOf<String>()
        val controller = ConversationController(this, { _, message, _ -> ids += message.id; "accepted" }, {})
        controller.restoreMemory(mapOf(key to memory)); controller.update(snapshot())
        assertEquals("unconfirmed", controller.session(key).optimistic.single().delivery)
        controller.retryPending(key, original.id); runCurrent()
        assertEquals(listOf("saved-id"), ids)
        controller.update(snapshot().copy(timeline = listOf(TimelineRow("server", "user_message", "pending", messageId = original.id))))
        assertTrue(controller.session(key).optimistic.isEmpty())
    }
    @Test fun canonicalHistoryClearsRecoveredFailureWithoutResendingIt() = runTest {
        val message = OutgoingMessage(id = "delivered", draft = ChatDraft("received"))
        val controller = ConversationController(this, { _, _, _ -> error("Must not resend") }, {})
        controller.restoreMemory(mapOf(key to ConversationMemory(draft = message.draft, failed = message)))
        controller.update(snapshot().copy(timeline = listOf(TimelineRow("server", "user_message", "received", messageId = message.id))))
        assertTrue(controller.session(key).draft.empty)
        assertNull(controller.session(key).failed)
        assertNull(controller.session(key).error)
    }
    @Test fun deletingHostDuringSendCannotResurrectItsDraft() = runTest {
        val receipt = CompletableDeferred<String>()
        val controller = ConversationController(this, { _, _, _ -> receipt.await() }, {})
        controller.update(snapshot()); controller.setDraft(key, "private draft"); controller.send(key); runCurrent()
        controller.forgetHost(key.hostId)
        receipt.completeExceptionally(IOException("closed")); runCurrent()
        assertFalse(controller.sessions.value.keys.any { it.hostId == key.hostId })
    }
    @Test fun stoppedTurnDrainsQueueEvenWhenReceiptForPreviousMessageIsStillPending() = runTest {
        val receipt = CompletableDeferred<String>()
        val texts = mutableListOf<String>()
        val controller = ConversationController(this, { _, message, _ -> texts += message.draft.text; if (texts.size == 1) receipt.await() else "accepted" }, {})
        controller.update(snapshot(true))
        controller.setDraft(key, "queued"); controller.send(key, true)
        controller.setDraft(key, "steer"); controller.send(key); runCurrent()
        controller.update(snapshot(false).copy(agents = listOf(Agent("a", status = "error"))))
        assertEquals(listOf("steer"), texts)
        receipt.complete("accepted"); runCurrent()
        assertEquals(listOf("steer", "queued"), texts)
    }
    @Test fun attachmentOnlyMessageIsValidAndStopRemainsLockedUntilTurnEnds() = runTest {
        var cancelled = 0
        var sent = 0
        val controller = ConversationController(this, { _, message, _ -> assertTrue(message.draft.text.isEmpty()); sent++; "accepted" }, { cancelled++ })
        controller.update(snapshot(true))
        controller.attach(key, listOf(ChatAttachment(name = "a.txt", payload = jsonObject("type" to "text", "text" to "test"))))
        controller.send(key); runCurrent(); assertEquals(1, sent)
        controller.stop(key); runCurrent(); controller.stop(key); runCurrent()
        assertEquals(1, cancelled)
        assertTrue(controller.session(key).cancelling)
        controller.update(snapshot(false)); assertFalse(controller.session(key).cancelling)
    }
}
