# Agent session & tab counter

Desktop utility (Compose for Desktop) that tracks **several AI coding agents at once** — session artifacts on disk, a rough **Chrome** tab count, and **live agent CLI processes** grouped by working directory. It is **not** specific to any single vendor; Hermes is just one row alongside Claude, Codex, Gemini, and Cursor.

## Monitored agents

Counts are derived from paths under your home directory (defaults target **macOS** layouts):

| Agent   | What is counted |
|--------|-----------------|
| **Hermes** | Unique session ids from `~/.hermes/sessions` and key count in `sessions.json` when present |
| **Claude** | Direct subfolders of the Claude Code history cache |
| **Codex** | Session-style files under `~/.codex/sessions` plus agent dirs under `~/.hermes/hermes-agent/agent` when present |
| **Gemini** | Recursive `json` / `jsonl` session files under common Gemini config locations |
| **Cursor** | Project folders under `~/.cursor/projects` and `~/.cursor-server` |

Chrome tab counting uses macOS `Library/Application Support/Google/Chrome/Default/Profile State` when available.

## Requirements

- **JDK 17+** (project uses JVM toolchain 17)
- **macOS** for the bundled path assumptions (other OSes may show zeros unless you adapt paths in code)

## Build & run

```bash
./gradlew run
```

Create installers (DMG / MSI / Deb per [Compose desktop packaging](https://github.com/JetBrains/compose-multiplatform)):

```bash
./gradlew packageDistributionForCurrentOS
```

## Stack

- Kotlin **2.3.20**, Compose Multiplatform **1.10.2**, `kotlinx-serialization` for JSON inspection
