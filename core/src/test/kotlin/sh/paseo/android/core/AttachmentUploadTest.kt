package sh.paseo.android.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class AttachmentUploadTest {
    @Test fun uploadsBinaryChunksWithOfficialFramingAndCorrelatesReceipt() = runBlocking {
        val output = ByteArrayOutputStream()
        var requested = jsonObject()
        var metadata = jsonObject()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text) as JsonObject
                if (frame.string("type") == "hello") webSocket.send(DaemonSocketTest.session("status", jsonObject("status" to "server_info")))
                else requested = frame.obj("message")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val buffer = ByteBuffer.wrap(bytes.toByteArray())
                val opcode = buffer.get().toInt() and 255
                val id = ByteArray(buffer.get().toInt() and 255).also { buffer.get(it) }.decodeToString()
                assertEquals(requested.string("requestId"), id)
                when (opcode) {
                    0x10 -> { val size = buffer.short.toInt() and 65535; metadata = wireJson.parseToJsonElement(ByteArray(size).also { buffer.get(it) }.decodeToString()) as JsonObject }
                    0x11 -> { assertTrue(buffer.remaining() <= 128 * 1024); output.write(ByteArray(buffer.remaining()).also { buffer.get(it) }) }
                    0x12 -> webSocket.send(DaemonSocketTest.session("file.upload.response", jsonObject("requestId" to id, "file" to jsonObject("type" to "uploaded_file", "id" to "f", "fileName" to "你好.bin", "mimeType" to "application/octet-stream", "size" to output.size(), "path" to "/uploads/f"))))
                    else -> error("Unexpected opcode")
                }
            }
        }))
        val socket = DaemonSocket()
        try {
            socket.open(Host(name = "fixture", url = server.url("/ws").toString()))
            val rejected = runCatching { byteArrayOf(1).inputStream().use { socket.uploadFile("wrong-host.bin", "application/octet-stream", 1, it, socket.connectionToken() - 1) } }
            assertTrue(rejected.isFailure)
            assertTrue(requested.isEmpty())
            val bytes = ByteArray(400_001) { (it % 251).toByte() }
            val file = bytes.inputStream().use { socket.uploadFile("你好.bin", "application/octet-stream", bytes.size.toLong(), it) }
            assertEquals("uploaded_file", file.string("type"))
            assertEquals("binary", metadata.string("encoding"))
            assertEquals(bytes.size.toLong(), requested.long("size"))
            assertArrayEquals(bytes, output.toByteArray())
        } finally { socket.close(); server.close() }
    }
}
