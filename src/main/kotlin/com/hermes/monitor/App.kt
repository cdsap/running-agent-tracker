package com.hermes.monitor

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val HOME_DIR = Path.of(System.getProperty("user.home"))
private val CHROME_DIR = HOME_DIR.resolve("Library/Application Support/Google/Chrome/Default/Profile State")

private enum class CountStrategy {
    DIRECT_CHILD_DIRECTORIES,
    RECURSIVE_SESSION_FILES,
    HERMES_UNIQUE_SESSIONS,
}

private data class AgentSource(
    val path: Path,
    val strategy: CountStrategy,
)

private data class AgentSpec(
    val label: String,
    val sources: List<AgentSource>,
    /** Hermes-style index: JSON object keys = concurrent routed chats/channels. */
    val sessionsIndexPath: Path? = null,
)

data class AgentSessionCount(
    val label: String,
    /** Stored session artifacts on disk (unique ids for Hermes; file/dir counts otherwise). */
    val count: Int,
    /** Entries in `sessions.json` when present — “active” routings, not total history. */
    val activeChannels: Int? = null,
)

private val agentSpecs = listOf(
    AgentSpec(
        label = "Hermes",
        sources = listOf(
            AgentSource(
                path = HOME_DIR.resolve(".hermes/sessions"),
                strategy = CountStrategy.HERMES_UNIQUE_SESSIONS,
            ),
        ),
        sessionsIndexPath = HOME_DIR.resolve(".hermes/sessions/sessions.json"),
    ),
    AgentSpec(
        label = "Claude",
        sources = listOf(
            AgentSource(
                path = HOME_DIR.resolve(".cache/AI/Claude/ClaudeCode/history"),
                strategy = CountStrategy.DIRECT_CHILD_DIRECTORIES,
            ),
        ),
    ),
    AgentSpec(
        label = "Codex",
        sources = listOf(
            AgentSource(
                path = HOME_DIR.resolve(".codex/sessions"),
                strategy = CountStrategy.RECURSIVE_SESSION_FILES,
            ),
            AgentSource(
                path = HOME_DIR.resolve(".hermes/hermes-agent/agent"),
                strategy = CountStrategy.DIRECT_CHILD_DIRECTORIES,
            ),
        ),
    ),
    AgentSpec(
        label = "Gemini",
        sources = listOf(
            AgentSource(
                path = HOME_DIR.resolve(".gemini"),
                strategy = CountStrategy.RECURSIVE_SESSION_FILES,
            ),
            AgentSource(
                path = HOME_DIR.resolve(".config/gemini"),
                strategy = CountStrategy.RECURSIVE_SESSION_FILES,
            ),
            AgentSource(
                path = HOME_DIR.resolve("Library/Application Support/Gemini"),
                strategy = CountStrategy.RECURSIVE_SESSION_FILES,
            ),
        ),
    ),
    AgentSpec(
        label = "Cursor",
        sources = listOf(
            AgentSource(
                path = HOME_DIR.resolve(".cursor/projects"),
                strategy = CountStrategy.DIRECT_CHILD_DIRECTORIES,
            ),
            AgentSource(
                path = HOME_DIR.resolve(".cursor-server"),
                strategy = CountStrategy.DIRECT_CHILD_DIRECTORIES,
            ),
        ),
    ),
)

private fun countDirectChildDirectories(path: Path): Int {
    if (!path.exists()) return 0
    return Files.list(path).use { paths ->
        paths.filter { Files.isDirectory(it) }.count().toInt()
    }
}

private fun isSessionFile(path: Path): Boolean {
    if (!Files.isRegularFile(path)) return false
    val fileName = path.fileName.toString()
    if (fileName == "sessions.json") return false
    if (fileName.startsWith("request_dump_")) return false
    return path.extension == "json" || path.extension == "jsonl"
}

private fun countRecursiveSessionFiles(path: Path): Int {
    if (!path.exists()) return 0
    return Files.walk(path).use { paths ->
        paths.filter(::isSessionFile).count().toInt()
    }
}

private fun hermesSessionIdFromPath(path: Path): String? {
    if (!isSessionFile(path)) return null
    val name = path.fileName.toString()
    return when {
        name.startsWith("session_") && name.endsWith(".json") ->
            name.removeSuffix(".json").removePrefix("session_")
        name.endsWith(".jsonl") ->
            name.removeSuffix(".jsonl")
        name.endsWith(".json") ->
            name.removeSuffix(".json")
        else -> null
    }
}

private fun countHermesUniqueSessions(path: Path): Int {
    if (!path.exists()) return 0
    val ids = mutableSetOf<String>()
    Files.walk(path).use { stream ->
        stream.filter(Files::isRegularFile).forEach { p ->
            hermesSessionIdFromPath(p)?.let { ids.add(it) }
        }
    }
    return ids.size
}

private fun countJsonObjectKeys(path: Path): Int {
    if (!path.exists()) return 0
    return try {
        val root = Json.parseToJsonElement(Files.readString(path))
        (root as? JsonObject)?.size ?: 0
    } catch (_: Exception) {
        0
    }
}

private fun countAgentSessions(spec: AgentSpec): Result<AgentSessionCount> {
    return try {
        val count = spec.sources.sumOf { source ->
            when (source.strategy) {
                CountStrategy.DIRECT_CHILD_DIRECTORIES -> countDirectChildDirectories(source.path)
                CountStrategy.RECURSIVE_SESSION_FILES -> countRecursiveSessionFiles(source.path)
                CountStrategy.HERMES_UNIQUE_SESSIONS -> countHermesUniqueSessions(source.path)
            }
        }
        val active: Int? = spec.sessionsIndexPath?.let { p ->
            if (p.exists()) countJsonObjectKeys(p) else null
        }
        Result.success(AgentSessionCount(spec.label, count, active))
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal fun countAllSessions(): Pair<Int, List<AgentSessionCount>> {
    val results = mutableListOf<AgentSessionCount>()
    var total = 0

    agentSpecs.forEach { spec ->
        countAgentSessions(spec).onSuccess { row ->
            results.add(row)
            total += row.count
        }
    }

    return total to results
}

internal fun countChromeTabs(): Result<Int> {
    return try {
        val cookieJar = Files.readString(CHROME_DIR)
        val pattern = """chrome-tab://(\d+)""".toRegex()
        Result.success(pattern.findAll(cookieJar).count())
    } catch (e: Exception) {
        if (e.message?.contains("No such file") == true || e is java.nio.file.NoSuchFileException) {
            Result.success(0)
        } else {
            Result.failure(e)
        }
    }
}

@Composable
fun CounterView(
    agentCounts: List<AgentSessionCount>,
    runningByCwd: List<RunningAgentByCwd>,
    runningProcessCount: Int,
    chromeTabsCount: Int,
    lastUpdated: String?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Agent & Browser Tab Counter",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                agentCounts.forEach { row ->
                    val display = buildString {
                        append(row.count)
                        row.activeChannels?.let { append(" ($it active)") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${row.label}:",
                            fontSize = 18.sp,
                            fontWeight = if (row.count > 0) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = display,
                            fontSize = 24.sp,
                            color = Color(0xFF2196F3),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Text(
                text = "Running now (by folder)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Text(
                text = if (runningProcessCount == 0) {
                    "No matching CLIs in ps — add regexes in RunningAgents.kt if needed."
                } else {
                    "$runningProcessCount process(es) in ${runningByCwd.size} folder(s)"
                },
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 6.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                runningByCwd.forEach { row ->
                    Text(
                        text = row.cwdDisplay,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = row.summaryLine,
                        fontSize = 12.sp,
                        color = Color(0xFF1565C0),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Chrome Tabs:",
                    fontSize = 18.sp,
                    fontWeight = if (chromeTabsCount > 0) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = chromeTabsCount.toString(),
                    fontSize = 24.sp,
                    color = Color(0xFFFF9800),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!lastUpdated.isNullOrEmpty()) {
                    Text(
                        text = "Last updated: $lastUpdated",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Refreshes every 5s",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
        }
    }
}

@Composable
@Preview
fun AppPreview() {
    val sampleAgents = listOf(
        AgentSessionCount("Hermes", 5, 1),
        AgentSessionCount("Claude", 2),
        AgentSessionCount("Codex", 1),
        AgentSessionCount("Gemini", 0),
        AgentSessionCount("Cursor", 3),
    )
    MaterialTheme {
        CounterView(
            agentCounts = sampleAgents,
            runningByCwd = listOf(
                RunningAgentByCwd("~/projects/alpha", "2× Claude  ·  pid 1001, 1002"),
                RunningAgentByCwd("~/work/beta", "1× Codex  ·  pid 2002"),
            ),
            runningProcessCount = 3,
            chromeTabsCount = 12,
            lastUpdated = "10:15 PM",
        )
    }
}

fun main() {
    if (GraphicsEnvironment.isHeadless()) {
        HeadlessTerminal.run()
        return
    }
    application {
    var agentCounts by remember { mutableStateOf(emptyList<AgentSessionCount>()) }
    var runningByCwd by remember { mutableStateOf(emptyList<RunningAgentByCwd>()) }
    var runningProcessCount by remember { mutableStateOf(0) }
    var chromeTabsCount by remember { mutableStateOf(0) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshCounts() {
        val (_, agentResults: List<AgentSessionCount>) = countAllSessions()
        agentCounts = agentResults

        val live = listRunningAgents()
        runningByCwd = summarizeAgentsByCwd(live)
        runningProcessCount = live.size

        countChromeTabs().onSuccess { count ->
            chromeTabsCount = count
            errorMessage = null
        }.onFailure { e ->
            if (errorMessage == null) {
                errorMessage = "Chrome count not available (is Chrome running?): ${e.message}"
            }
        }

        lastUpdated = java.time.LocalTime.now().withNano(0).toString()
    }

    LaunchedEffect(Unit) {
        refreshCounts()
        while (true) {
            delay(TimeUnit.SECONDS.toMillis(5))
            refreshCounts()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Hermes Session & Tab Counter",
        state = androidx.compose.ui.window.rememberWindowState(width = 520.dp, height = 520.dp),
    ) {
        MaterialTheme {
            Surface(color = MaterialTheme.colors.surface) {
                Column(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = { refreshCounts() },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(8.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh")
                    }

                    Divider()

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = Color.Red,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    CounterView(
                        agentCounts = agentCounts,
                        runningByCwd = runningByCwd,
                        runningProcessCount = runningProcessCount,
                        chromeTabsCount = chromeTabsCount,
                        lastUpdated = lastUpdated,
                    )
                }
            }
        }
    }
    }
}
