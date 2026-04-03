# Running agent tracker

Desktop utility (Compose for Desktop) that lists **AI agent CLIs that are running right now**: which binary, **PID**, **current working directory** (via `lsof` / `/proc`), and a **scheduler hint** derived from `ps` state (`R` active, `S`/`I` idle or sleeping, `T` stopped, `Z` zombie). It does **not** scan session folders on disk—the focus is live processes.

## Who gets detected

Processes whose command line matches heuristics in `RunningAgents.kt` (Claude, Codex, Gemini, Hermes, Cursor, etc.). Extend the regex list there for your tools.

**Idle / waiting** is normal: most agent processes sleep between I/O and tool calls. **Active** (`R`) may appear only briefly in a 5s snapshot.

## Requirements

- **JDK 17+**
- **macOS** or **Linux** for `ps` and cwd resolution (`lsof` on macOS for cwd when `/proc` is not used)

If cwd shows as unknown, macOS may need permission for the app (or parent terminal) to inspect other processes—try running from a full-access context or check **Full Disk Access** for `lsof`.

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
