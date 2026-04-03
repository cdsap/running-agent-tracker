package com.agentsessiontab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Coarse activity hint from the OS scheduler (same letters `ps` uses: R run, S sleep, I idle, etc.).
 */
enum class ProcessActivityKind {
    /** Runnable / on CPU (R, sometimes rare in a snapshot). */
    Active,
    /** Sleeping, idle, or waiting on I/O — normal for most agent CLIs between tool calls. */
    IdleOrWaiting,
    /** Stopped (job control). */
    Stopped,
    /** Defunct process. */
    Zombie,
    Unknown,
}

data class ProcessActivity(
    val kind: ProcessActivityKind,
    /** Raw `state` / `stat` field from ps. */
    val rawState: String,
) {
    val shortLabel: String
        get() = when (kind) {
            ProcessActivityKind.Active -> "Active"
            ProcessActivityKind.IdleOrWaiting -> "Idle / waiting"
            ProcessActivityKind.Stopped -> "Stopped"
            ProcessActivityKind.Zombie -> "Zombie"
            ProcessActivityKind.Unknown -> "State ?"
        }
}

/**
 * Live agent CLI matched from argv, with cwd, scheduler state, uptime, and resource snapshot from `ps`.
 */
data class RunningAgent(
    val label: String,
    val pid: Long,
    val activity: ProcessActivity,
    /** True working directory when resolved (see [cwdResolutionNote]). */
    val cwd: String?,
    /** Hint when [cwd] could not be resolved or looks wrong. */
    val cwdResolutionNote: String?,
    /** Short command line for disambiguation. */
    val argvPreview: String,
    /** Process elapsed time as reported by ps (`etime`), e.g. `02:15:30` or `1-03:00:00`. */
    val uptime: String,
    /** Resident set size in KiB from ps, if parse succeeded. */
    val rssKiB: Long?,
    /** CPU % since process start (or last snapshot semantics per OS) from ps. */
    val cpuPercent: Double?,
)

private val homeDir = System.getProperty("user.home") ?: ""

private val agentMatchers: List<Pair<Regex, String>> = listOf(
    // Claude Code — argv is messy: `claude` on PATH, Homebrew path, node/bun/npx + @anthropic-ai/*, npx cache dirs
    Regex("""@anthropic-ai/claude-code""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""claude-code""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""@anthropic-ai/claude""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""\b(bun|deno|node|npm|npx)\s+.*@anthropic-ai/claude""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""\b(bun|deno|node)\s+.*claude-code""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""^claude(\.exe)?(\s|$)""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""[/\\]claude(?!-desktop)(\.exe)?(\s|$)""", RegexOption.IGNORE_CASE) to "Claude",
    Regex("""(openai\.cli\.codex|codex-cli|/bin/codex(\s|$)|/.local/bin/codex(\s|$))""", RegexOption.IGNORE_CASE) to "Codex",
    Regex("""(gemini-cli|@google/gemini|google-gemini-cli)""", RegexOption.IGNORE_CASE) to "Gemini",
    Regex("""[/]gemini(\s|$)""", RegexOption.IGNORE_CASE) to "Gemini",
    Regex("""hermes-agent|hermes\s+monitor""", RegexOption.IGNORE_CASE) to "Hermes",
    Regex("""cursor-agent""", RegexOption.IGNORE_CASE) to "Cursor",
)

internal fun classifyProcessState(raw: String): ProcessActivity {
    val s = raw.trim()
    if (s.isEmpty()) return ProcessActivity(ProcessActivityKind.Unknown, raw)
    val c = s.first()
    return when (c.uppercaseChar()) {
        'R' -> ProcessActivity(ProcessActivityKind.Active, raw)
        'T', 't' -> ProcessActivity(ProcessActivityKind.Stopped, raw)
        'Z' -> ProcessActivity(ProcessActivityKind.Zombie, raw)
        'S', 'I', 'D', 'U', 'W' -> ProcessActivity(ProcessActivityKind.IdleOrWaiting, raw)
        else -> ProcessActivity(ProcessActivityKind.Unknown, raw)
    }
}

private fun classifyAgent(argv: String): String? {
    for ((regex, label) in agentMatchers) {
        if (regex.containsMatchIn(argv)) return label
    }
    return null
}

/** pid, state, rss(KiB), etime, pcpu, args… */
private val psExtendedLine =
    Regex("""^\s*(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+([\d.,]+)\s+(.*)$""")

private data class ParsedPs(
    val pid: Long,
    val state: String,
    val rssKiB: Long?,
    val etime: String,
    val pcpu: Double?,
    val argv: String,
)

private fun parsePsLine(line: String): ParsedPs? {
    val trimmed = line.trim()
    val m = psExtendedLine.matchEntire(trimmed) ?: return null
    val pid = m.groupValues[1].toLongOrNull() ?: return null
    val state = m.groupValues[2]
    val rssRaw = m.groupValues[3]
    val rssKiB = rssRaw.toLongOrNull()?.takeIf { it >= 0 }
    val etime = m.groupValues[4]
    val cpuRaw = m.groupValues[5].replace(',', '.')
    val pcpu = cpuRaw.toDoubleOrNull()
    val argv = m.groupValues[6].trim()
    if (argv.isEmpty()) return null
    return ParsedPs(pid, state, rssKiB, etime, pcpu, argv)
}

private fun resolveCwd(pid: Long): Pair<String?, String?> {
    val os = System.getProperty("os.name").lowercase()
    if (!os.contains("mac") && !os.contains("darwin")) {
        val procCwd = Path.of("/proc/$pid/cwd")
        if (Files.isSymbolicLink(procCwd) || Files.exists(procCwd)) {
            try {
                val target = Files.readSymbolicLink(procCwd).toString()
                return target to null
            } catch (_: Exception) {
                // try lsof below
            }
        }
    }
    cwdViaLsofFn(pid)?.let { return it to null }
    val legacy = cwdViaLsofTable(pid)
    if (legacy != null) return legacy to null
    return null to "Could not read cwd (try Full Disk Access for `lsof` on macOS, or run as the same user as the agent)."
}

private fun lsofExecutable(): String {
    val candidates = listOf("/usr/sbin/lsof", "/usr/bin/lsof")
    return candidates.firstOrNull { Files.isExecutable(Path.of(it)) } ?: "lsof"
}

/** Stable machine-oriented cwd path (`fcwd` / `n/path`). */
private fun cwdViaLsofFn(pid: Long): String? {
    val pb = ProcessBuilder(
        lsofExecutable(),
        "-a",
        "-p",
        pid.toString(),
        "-d",
        "cwd",
        "-Fn",
        "-n",
        "-P",
    )
    pb.redirectErrorStream(true)
    return try {
        val proc = pb.start()
        val lines = proc.inputStream.bufferedReader().use { br -> br.lineSequence().toList() }
        proc.waitFor(2, TimeUnit.SECONDS)
        var afterCwd = false
        for (line in lines) {
            when {
                line == "fcwd" -> afterCwd = true
                afterCwd && line.startsWith("n") && line.length > 1 -> {
                    return line.substring(1)
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

/** Older wide-table parsing (NAME column). */
private fun cwdViaLsofTable(pid: Long): String? {
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

private fun previewArgv(argv: String, maxLen: Int = 96): String {
    val oneLine = argv.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= maxLen) oneLine else oneLine.take(maxLen - 1) + "…"
}

internal fun shortenHomePath(absolute: String): String {
    if (homeDir.isNotEmpty() && absolute.startsWith(homeDir)) {
        return "~" + absolute.substring(homeDir.length)
    }
    return absolute
}

internal fun formatRssKiB(kib: Long?): String {
    if (kib == null || kib <= 0) return "—"
    if (kib >= 1024 * 1024) return String.format("%.1f GiB", kib / 1024.0 / 1024.0)
    if (kib >= 1024) return String.format("%.1f MiB", kib / 1024.0)
    return "$kib KiB"
}

/** Single line for cwd + permission hints (Compose + headless). */
internal fun cwdDisplayForUi(agent: RunningAgent): String = when {
    agent.cwd != null -> {
        val path = agent.cwd
        if (Files.exists(Path.of(path))) {
            shortenHomePath(path)
        } else {
            "${shortenHomePath(path)} · path not visible to this app (permissions / sandbox?)"
        }
    }
    else -> agent.cwdResolutionNote ?: "Working directory unknown."
}

internal fun listRunningAgents(): List<RunningAgent> {
    val os = System.getProperty("os.name").lowercase()
    val wide = if (os.contains("mac") || os.contains("darwin")) {
        listOf("ps", "-axww", "-o", "pid=,state=,rss=,etime=,pcpu=,args=")
    } else {
        listOf("ps", "-ww", "-eo", "pid=,stat=,rss=,etime=,pcpu=,args=")
    }
    val pb = ProcessBuilder(wide)
    pb.redirectErrorStream(true)
    val lines: List<String> = try {
        val proc = pb.start()
        val psText = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(4, TimeUnit.SECONDS)
        psText.lineSequence().toList()
    } catch (_: Exception) {
        emptyList()
    }

    val seen = mutableSetOf<Long>()
    val agents = mutableListOf<RunningAgent>()
    for (line in lines) {
        if (line.isBlank()) continue
        val parsed = parsePsLine(line) ?: continue
        if (!seen.add(parsed.pid)) continue
        val label = classifyAgent(parsed.argv) ?: continue
        val (cwd, cwdNote) = resolveCwd(parsed.pid)
        agents.add(
            RunningAgent(
                label = label,
                pid = parsed.pid,
                activity = classifyProcessState(parsed.state),
                cwd = cwd,
                cwdResolutionNote = cwdNote,
                argvPreview = previewArgv(parsed.argv),
                uptime = parsed.etime,
                rssKiB = parsed.rssKiB,
                cpuPercent = parsed.pcpu,
            ),
        )
    }
    return agents.sortedWith(compareBy({ it.label }, { it.pid }))
}
