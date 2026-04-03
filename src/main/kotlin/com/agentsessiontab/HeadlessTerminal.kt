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
        |$cyan  /--|  $white(o o)$cyan  |--\ $dim  Agent session tab$reset
        |$cyan /   |  $white\ ^ /$cyan  |   \\
        |$cyan/    '.__$white\m/${cyan}__.'    \\
        |$cyan       /  $yellow>>$cyan  \ $yellow~~$reset$dim  ~ tux approves ~/.sessions ~$reset
        |$cyan      /________\\
    """.trimMargin().trim()

    fun run() {
        Runtime.getRuntime().addShutdownHook(thread(start = false) { print(reset) })

        while (true) {
            val (_, agents) = countAllSessions()
            val running = listRunningAgents()
            val byCwd = summarizeAgentsByCwd(running)
            val chromeResult = countChromeTabs()
            val stamp = java.time.LocalTime.now().withNano(0)

            print(clearScreen)
            println(tux)
            println()
            println("$bold$cyan══ Agent session & tab counter $dim(headless)$cyan ══$reset")
            println("$dim$stamp  ·  every 5s  ·  Ctrl+C to quit$reset")
            println()

            agents.forEach { row ->
                val label = row.label.padEnd(10)
                val n = buildString {
                    append(row.count)
                    row.activeChannels?.let { append(" ($it active)") }
                }
                println("  $bold$label$reset  $green$n$reset")
            }

            println()
            println("$bold$cyan══ Running agents (by cwd) ══$reset")
            if (byCwd.isEmpty()) {
                println("  $dim(no processes matched claude-code / codex / gemini / … — edit RunningAgents.kt)$reset")
            } else {
                println("  $dim${running.size} process(es) · ${byCwd.size} dir(s)$reset")
                println()
                val cap = 40
                byCwd.take(cap).forEach { g ->
                    println("  $bold${g.cwdDisplay}$reset")
                    println("    $dim${g.summaryLine}$reset")
                }
                if (byCwd.size > cap) {
                    println("  $dim… ${byCwd.size - cap} more directories$reset")
                }
            }

            println()
            chromeResult.onSuccess { tabs ->
                println("  ${bold}Chrome tabs$reset  $yellow$tabs$reset")
            }.onFailure { e ->
                println("  ${bold}Chrome$reset     $dim${e.message}$reset")
            }

            println()
            println("${dim}Open a desktop session for the full Compose UI.$reset")

            Thread.sleep(5_000)
        }
    }
}
