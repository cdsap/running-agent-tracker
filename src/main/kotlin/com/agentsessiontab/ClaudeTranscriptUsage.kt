package com.agentsessiontab

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the latest main-chain token usage from Claude Code JSONL under ~/.claude/projects/
 * (best-effort: picks the newest .jsonl that mentions the process cwd in head/tail).
 */
object ClaudeTranscriptUsage {

    private val reInput = """"input_tokens"\s*:\s*(\d+)""".toRegex()
    private val reOutput = """"output_tokens"\s*:\s*(\d+)""".toRegex()
    private val rePrompt = """"prompt_tokens"\s*:\s*(\d+)""".toRegex()
    private val reCompletion = """"completion_tokens"\s*:\s*(\d+)""".toRegex()
    private val reCacheRead = """"cache_read_input_tokens"\s*:\s*(\d+)""".toRegex()
    private val reCacheCreate = """"cache_creation_input_tokens"\s*:\s*(\d+)""".toRegex()
    private val reUsedPct = """"used_percentage"\s*:\s*([\d.]+)""".toRegex()
    private val reSidechainTrue = """"isSidechain"\s*:\s*true""".toRegex()

    private data class Snap(
        val input: Int,
        val output: Int,
        val cacheRead: Int?,
        val cacheCreate: Int?,
        val contextPct: Double?,
    )

    fun summarizeLastUsage(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        val home = System.getProperty("user.home") ?: return null
        val jsonl = findJsonlForCwd(home, cwd) ?: return null
        val snap = latestMainChainUsage(jsonl) ?: return null
        return formatSnap(snap)
    }

    private fun formatSnap(s: Snap): String {
        val p = mutableListOf<String>()
        p.add("${s.input} in · ${s.output} out")
        s.cacheRead?.takeIf { it > 0 }?.let { p.add("cache rd $it") }
        s.cacheCreate?.takeIf { it > 0 }?.let { p.add("cache wr $it") }
        s.contextPct?.let { p.add("~${it}% context") }
        return p.joinToString(" · ")
    }

    private fun findJsonlForCwd(home: String, cwd: String): Path? {
        val root = Path.of(home, ".claude/projects")
        if (!Files.isDirectory(root)) return null
        val norm = Path.of(cwd).normalize().toString().replace('\\', '/').trimEnd('/')
        val rel = norm.removePrefix(home.replace('\\', '/')).trimStart('/')
        var best: Path? = null
        var bestTime = -1L
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".jsonl") }
                .forEach { p ->
                    if (FILES_SIZE_isZero(p)) return@forEach
                    if (!fileMentionsCwd(p, norm, rel)) return@forEach
                    val t = Files.getLastModifiedTime(p).toMillis()
                    if (t >= bestTime) {
                        bestTime = t
                        best = p
                    }
                }
        }
        return best
    }

    private fun FILES_SIZE_isZero(p: Path): Boolean = try {
        Files.size(p) == 0L
    } catch (_: Exception) {
        true
    }

    private fun fileMentionsCwd(p: Path, norm: String, rel: String): Boolean {
        val head = readBytes(p, 0L, 16_384)
        val sz = try {
            Files.size(p)
        } catch (_: Exception) {
            return false
        }
        val tail = readBytes(p, (sz - 48_384).coerceAtLeast(0), 48_384)
        val text = String(head + tail, StandardCharsets.UTF_8)
        if (text.contains(norm)) return true
        if (rel.isNotEmpty() && text.contains(rel)) return true
        return false
    }

    private fun readBytes(p: Path, start: Long, maxLen: Int): ByteArray {
        return try {
            RandomAccessFile(p.toFile(), "r").use { raf ->
                val size = raf.length()
                val pos = start.coerceIn(0L, size)
                raf.seek(pos)
                val n = kotlin.math.min(maxLen.toLong(), size - pos).toInt()
                if (n <= 0) return ByteArray(0)
                val buf = ByteArray(n)
                raf.readFully(buf)
                buf
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun latestMainChainUsage(path: Path): Snap? {
        val text = try {
            val sz = Files.size(path)
            if (sz <= 750_000L) {
                Files.readString(path, StandardCharsets.UTF_8)
            } else {
                readTailString(path, 450_000)
            }
        } catch (_: Exception) {
            return null
        }
        val lines = text.lines()
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (line.isBlank()) continue
            val snap = parseLine(line) ?: continue
            return snap
        }
        return null
    }

    private fun readTailString(path: Path, maxChars: Int): String {
        return RandomAccessFile(path.toFile(), "r").use { raf ->
            val size = raf.length()
            val n = kotlin.math.min(maxChars.toLong(), size).toInt()
            raf.seek(size - n)
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, StandardCharsets.UTF_8)
        }
    }

    private fun parseLine(line: String): Snap? {
        if (!line.contains("usage") && !line.contains("tokens")) return null
        if (reSidechainTrue.containsMatchIn(line)) return null
        val inp = reInput.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: rePrompt.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val out = reOutput.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: reCompletion.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val cr = reCacheRead.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val cc = reCacheCreate.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val pct = reUsedPct.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        return Snap(inp, out, cr, cc, pct)
    }
}
