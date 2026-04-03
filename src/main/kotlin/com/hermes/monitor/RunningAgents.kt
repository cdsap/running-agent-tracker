package com.hermes.monitor

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Live agent CLIs tied to a working directory — answers “where is this session actually running?”
 * for crowded terminal setups.
 */
data class RunningAgent(
    val label: String,
    val pid: Long,
    /** Resolved cwd when available; null if lsof/proc failed or disallowed. */
    val cwd: String?,
    /** Short command line for disambiguation. */
    val argvPreview: String,
)

/**
 * One directory with one line of summary text for cramped UIs.
 */
data class RunningAgentByCwd(
    val cwdDisplay: String,
    val summaryLine: String,
)

private val homeDir = System.getProperty("user.home") ?: ""

private val agentMatchers: List<Pair<Regex, String>> = listOf(
    Regex("""claude-code""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""@anthropic-ai/claude""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""[/]claude(?!-desktop)(\s|$)""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""(openai\.cli\.codex|codex-cli|/bin/codex(\s|$)|/.local/bin/codex(\s|$))""", RegexOption.IGNORE_CASE) to "Codex",
    Regex("""(gemini-cli|@google/gemini|google-gemini-cli)""", RegexOption.IGNORE_CASE) to "Gemini",
    Regex("""[/]gemini(\s|$)""", RegexOption.IGNORE_CASE) to "Gemini",
    Regex("""hermes-agent|hermes\s+monitor""", RegexOption.IGNORE_CASE) to "Hermes",
    Regex("""cursor-agent""", RegexOption.IGNORE_CASE) to "Cursor",
)

private fun classifyAgent(argv: String): String? {
    for ((regex, label) in agentMatchers) {
        if (regex.containsMatchIn(argv)) return label
    }
    return null
}

private fun parsePsLine(line: String): Pair<Long, String>? {
    val trimmed = line.trimStart()
    val sp = trimmed.indexOf(' ')
    if (sp <= 0) return null
    val pid = trimmed.substring(0, sp).trim().toLongOrNull() ?: return null
    val argv = trimmed.substring(sp + 1).trim()
    if (argv.isEmpty()) return null
    return pid to argv
}

private fun resolveCwd(pid: Long): String? {
    val os = System.getProperty("os.name").lowercase()
    if (!os.contains("mac") && !os.contains("darwin")) {
        val procCwd = Path.of("/proc/$pid/cwd")
        if (Files.isSymbolicLink(procCwd) || Files.exists(procCwd)) {
            try {
                return Files.readSymbolicLink(procCwd).toString()
            } catch (_: Exception) {
                // fall through to lsof
            }
        }
    }
    return cwdViaLsof(pid)
}

private fun lsofExecutable(): String {
    val candidates = listOf("/usr/sbin/lsof", "/usr/bin/lsof")
    return candidates.firstOrNull { Files.isExecutable(Path.of(it)) } ?: "lsof"
}

private fun cwdViaLsof(pid: Long): String? {
    val pb = ProcessBuilder(lsofExecutable(), "-a", "-p", pid.toString(), "-d", "cwd", "-n", "-P")
    pb.redirectErrorStream(true)
    return try {
        val proc = pb.start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(2, TimeUnit.SECONDS)
        val line = text.lineSequence().firstOrNull { it.contains(" cwd ") && it.contains(" DIR ") }
            ?: return null
        val idx = line.indexOf('/')
        if (idx < 0) null else line.substring(idx).trim()
    } catch (_: Exception) {
        null
    }
}

private fun previewArgv(argv: String, maxLen: Int = 72): String {
    val oneLine = argv.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= maxLen) oneLine else oneLine.take(maxLen - 1) + "…"
}

internal fun shortenHomePath(absolute: String): String {
    if (homeDir.isNotEmpty() && absolute.startsWith(homeDir)) {
        return "~" + absolute.substring(homeDir.length)
    }
    return absolute
}

internal fun listRunningAgents(): List<RunningAgent> {
    val os = System.getProperty("os.name").lowercase()
    val cmd = if (os.contains("mac") || os.contains("darwin")) {
        listOf("ps", "-ax", "-o", "pid=,args=")
    } else {
        listOf("ps", "-eo", "pid=,args=")
    }
    val pb = ProcessBuilder(cmd)
    pb.redirectErrorStream(true)
    val lines: List<String> = try {
        val proc = pb.start()
        val psText = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(3, TimeUnit.SECONDS)
        psText.lineSequence().toList()
    } catch (_: Exception) {
        emptyList()
    }

    val seen = mutableSetOf<Long>()
    val agents = mutableListOf<RunningAgent>()
    for (line in lines) {
        if (line.isBlank()) continue
        val (pid, argv) = parsePsLine(line) ?: continue
        if (!seen.add(pid)) continue
        val label = classifyAgent(argv) ?: continue
        val cwd = resolveCwd(pid)
        agents.add(
            RunningAgent(
                label = label,
                pid = pid,
                cwd = cwd,
                argvPreview = previewArgv(argv),
            ),
        )
    }
    return agents
}

internal fun summarizeAgentsByCwd(agents: List<RunningAgent>): List<RunningAgentByCwd> {
    if (agents.isEmpty()) return emptyList()
    val byCwd = agents.groupBy { it.cwd ?: "(cwd unknown)" }
    return byCwd.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<RunningAgent>>> { it.value.size }.thenBy { it.key })
        .map { (cwd, rows) ->
            val display = if (cwd == "(cwd unknown)") cwd else shortenHomePath(cwd)
            val byLabel = rows.groupingBy { it.label }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value}× ${it.key}" }
            val pids = rows.map { it.pid }.sorted().joinToString(", ")
            RunningAgentByCwd(
                cwdDisplay = display,
                summaryLine = "$byLabel  ·  pid $pids",
            )
        }
}
