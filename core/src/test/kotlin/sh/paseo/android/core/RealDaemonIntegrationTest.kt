package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in only. Use a disposable daemon home and a seeded "Real daemon workspace". */
class RealDaemonIntegrationTest {
    @Test fun filesAndTerminalAgainstAnIsolatedOfficialDaemon() = runBlocking {
        val url = System.getenv("PASEO_SMOKE_URL").orEmpty()
        val linkFile = System.getenv("PASEO_SMOKE_LINK_FILE").orEmpty()
        val passwordFile = System.getenv("PASEO_SMOKE_PASSWORD_FILE").orEmpty()
        assumeTrue("Set PASEO_SMOKE_URL or PASEO_SMOKE_LINK_FILE to a disposable daemon", url.isNotBlank() || linkFile.isNotBlank())
        val endpoint = Endpoint.parse(if (linkFile.isNotBlank()) java.io.File(linkFile).readText().trim() else url)
        val password = if (passwordFile.isNotBlank()) java.io.File(passwordFile).readText().trim() else endpoint.password.orEmpty()
        val repository = PaseoRepository()
        var terminalId: String? = null
        try {
            repository.connect(Host(name = "Real daemon", url = endpoint.url, password = password, relay = endpoint.relay))
            val state = withTimeout(30_000) { repository.state.first { it.catalogLoaded } }
            val workspace = state.workspaces.single { it.name == "Real daemon workspace" }
            repository.openWorkspace(workspace)
            assertTrue(repository.state.value.workspaceAgents.isEmpty())
            val file = repository.files(workspace.cwd, "README.md", true).obj("file")
            assertTrue(file.string("content").contains("Native integration"))
            val updated = file.string("content") + "\nNative Kotlin file write check.\n"
            repository.saveFile(workspace.cwd, file, updated)
            assertEquals(updated, repository.files(workspace.cwd, "README.md", true).obj("file").string("content"))
            val terminal = repository.createTerminal(workspace.context)
            terminalId = terminal.string("id")
            assertTrue(terminalId.isNotBlank())
            repository.terminalInput(terminalId, "printf '%s%s\\n' NATIVE_ TERMINAL_OK\r")
            withTimeout(10_000) {
                while (!repository.captureTerminal(terminalId).contains("NATIVE_TERMINAL_OK")) delay(200)
            }
            assertTrue(repository.terminals(workspace.context).any { it.string("id") == terminalId })
        } finally {
            try { terminalId?.let { repository.closeTerminal(it) } } finally { repository.close() }
        }
    }
}
