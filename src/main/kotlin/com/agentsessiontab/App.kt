package com.agentsessiontab

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private val TrackerDark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF061018),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE8EEF4),
    outline = Color(0xFF3A4D5C),
    surfaceVariant = Color(0xFF151B22),
    onSurfaceVariant = Color(0xFF9FB0C0),
)

@Composable
private fun TrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TrackerDark, content = content)
}

@Composable
private fun AgentRow(agent: RunningAgent) {
    val path = cwdDisplayForUi(agent)
    val rss = formatRssKiB(agent.rssKiB)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = agent.label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = path,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "pid ${agent.pid}   up ${agent.uptime}   rss $rss",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun CounterView(
    runningAgents: List<RunningAgent>,
    lastUpdated: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        if (runningAgents.isEmpty()) {
            Text(
                text = "No agent CLIs in ps.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            runningAgents.forEachIndexed { i, agent ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AgentRow(agent)
            }
        }
        lastUpdated?.let {
            Text(
                text = "updated $it · 5s",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
@Preview
fun AppPreview() {
    val sample = listOf(
        RunningAgent(
            label = "Claude",
            pid = 1001L,
            activity = ProcessActivity(ProcessActivityKind.IdleOrWaiting, "S"),
            cwd = "/Users/me/projects/demo",
            cwdResolutionNote = null,
            argvPreview = "claude",
            uptime = "01:42:07",
            rssKiB = 186_000L,
            cpuPercent = 1.2,
        ),
    )
    TrackerTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            CounterView(sample, "12:00")
        }
    }
}

fun main() {
    if (GraphicsEnvironment.isHeadless()) {
        HeadlessTerminal.run()
        return
    }
    application {
        var runningAgents by remember { mutableStateOf(emptyList<RunningAgent>()) }
        var lastUpdated by remember { mutableStateOf<String?>(null) }

        fun refresh() {
            runningAgents = listRunningAgents()
            lastUpdated = java.time.LocalTime.now().withNano(0).toString()
        }

        LaunchedEffect(Unit) {
            refresh()
            while (true) {
                delay(TimeUnit.SECONDS.toMillis(5))
                refresh()
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Agent tracker",
            state = androidx.compose.ui.window.rememberWindowState(width = 520.dp, height = 620.dp),
        ) {
            TrackerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Agents",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            TextButton(onClick = { refresh() }) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Refresh")
                            }
                        }
                        CounterView(runningAgents, lastUpdated)
                    }
                }
            }
        }
    }
}
