# Running agent tracker

Desktop utility (Compose for Desktop, dark Material 3 UI) that lists **terminal / CLI agent processes**: **label**, **cwd**, **PID**, **`ps` uptime**, and **RSS**. **Claude Desktop** (the `.app` GUI) is **intentionally ignored** so the list matches “what’s running in the shell,” not the Electron app.

## Who gets detected

Processes whose command line matches heuristics in `RunningAgents.kt` (Claude Code, **Codex** — including `@openai/codex`, bare `codex`, `openai.cli.codex`, **Gemini** CLI, Hermes, Cursor, …). **Terminal emulators are not listed** — only the agent CLI process (e.g. `gemini`, `@google/gemini-cli`, `npx … gemini`). Gemini matches `@google/genai`, `uvx … gemini`, `gcloud … gemini`, bare `gemini` on `PATH`, etc. If something is missing, run `ps axww | grep -iE 'gemini|claude'` and extend the regex list.

**Idle / waiting** is normal: most agent processes sleep between I/O and tool calls. **Active** (`R`) may appear only briefly in a 5s snapshot.

## Requirements

- **JDK 17+**
- **macOS** or **Linux** for `ps` and cwd resolution (`lsof` on macOS for cwd when `/proc` is not used)

If **cwd** stays unknown or wrong, the tracker process must be allowed to inspect others: on macOS grant **Full Disk Access** (or equivalent) to the JVM/terminal running the app so `lsof` can read cwd; on Linux run as a user that can read `/proc/<pid>/cwd` and run `lsof`.

## Build & run

```bash
./gradlew run
```

Packaging:

```bash
./gradlew packageDistributionForCurrentOS
```

## Stack

- Kotlin **2.3.20**, Compose Multiplatform **1.10.2**
