# Running agent tracker

Desktop utility (Compose for Desktop) that lists **AI agent CLIs that are running right now**: **PID**, **cwd** (macOS: `lsof -Fn` on the cwd fd, then classic `lsof` table; Linux: `/proc/pid/cwd` then `lsof`), **upt** (**`etime`** from `ps`), **CPU %** and **RSS** from `ps`, plus scheduler **state** (`R` / `S` / …). **Token or context-window usage** is not available from the OS—use each vendor’s own UI or logs. It does **not** scan session folders on disk.

## Who gets detected

Processes whose command line matches heuristics in `RunningAgents.kt` (Claude, Codex, Gemini, Hermes, Cursor, etc.). Claude Code is matched on many argv shapes (`claude` on `PATH`, `@anthropic-ai/claude-code`, `node`/`npx`/`bun` wrappers, etc.); if yours still does not appear, run `ps axww | grep -i claude`, note the exact `args` column, and add a regex in `RunningAgents.kt`.

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
