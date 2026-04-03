package com.agentsessiontab

import kotlin.concurrent.thread

/**
 * When no AWT display is available (SSH, CI, Docker, etc.), show counts in the terminal
 * instead of failing with HeadlessException.
 */
object HeadlessTerminal {
    private const val CSI = "\u001b["
    private val clearScreen = "${CSI}2J${CSI}H"
    private val reset = "${CSI}0m"
    private val bold = "${CSI}1m"
    private val dim = "${CSI}2m"
    private val cyan = "${CSI}36m"
    private val green = "${CSI}32m"
    private val yellow = "${CSI}33m"
    private val white = "${CSI}97m"

    private val tux = """
        |$cyan       .___.$reset
        |$cyan      /     \ $white   ___$reset
        |$cyan  /--|  $white(o o)$cyan  |--\ $dim  Running agents$reset
        |$cyan /   |  $white\ ^ /$cyan  |   \\
        |$cyan/    '.__$white\m/${cyan}__.'    \\
        |$cyan       /  $yellow>>$cyan  \ $yellow~~$reset$dim  ~ ps + cwd ~$reset
        |$cyan      /________\\
    """.trimMargin().trim()

    fun run() {
        Runtime.getRuntime().addShutdownHook(thread(start = false) { print(reset) })

        while (true) {
            val running = listRunningAgents()
            val stamp = java.time.LocalTime.now().withNano(0)

            print(clearScreen)
            println(tux)
            println()
            println("$bold$cyan══ Running agent tracker $dim(headless)$cyan ══$reset")
            println("$dim$stamp  ·  every 5s  ·  Ctrl+C to quit$reset")
            println()

            if (running.isEmpty()) {
                println("  $dim(no matching agent CLIs in ps — start claude-code, codex, …)$reset")
            } else {
                println("  $dim${running.size} process(es)$reset")
                println()
                running.forEach { a ->
                    val loc = if (a.cwd == null) "(cwd unknown)" else shortenHomePath(a.cwd)
                    val act = when (a.activity.kind) {
                        ProcessActivityKind.Active -> green
                        ProcessActivityKind.IdleOrWaiting -> cyan
                        ProcessActivityKind.Stopped -> yellow
                        ProcessActivityKind.Zombie -> "${CSI}31m"
                        ProcessActivityKind.Unknown -> dim
                    }
                    println("  $bold${a.label}$reset  ${act}${a.activity.shortLabel}$reset  ${dim}ps ${a.activity.rawState}$reset")
                    println("    $dim$loc$reset")
                    println("    $dim pid ${a.pid} · ${a.argvPreview}$reset")
                    println()
                }
            }

            println()
            println("${dim}Idle/sleep between tool calls is normal · open a desktop session for the Compose UI$reset")

            Thread.sleep(5_000)
        }
    }
}
