<img src="docs/icon.svg" width="96" alt="VibeNotes app icon">

# VibeNotes

[![Android CI](https://github.com/MAppleDm/VibeNotes/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/MAppleDm/VibeNotes/actions/workflows/android.yml) [![Release](https://img.shields.io/github/v/release/MAppleDm/VibeNotes)](https://github.com/MAppleDm/VibeNotes/releases/latest) [![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

**English** · [Русский](README.ru.md)

Markdown notes, Kanban tasks, and your Android calendar through one chat interface.

Write “doctor on Thursday morning” and the assistant proposes an event you can review before adding it. Notes and boards stay in ordinary Markdown files, including Obsidian Kanban-compatible boards.

## Features

- **One inbox:** turn messages into notes, tasks, or calendar proposals; review changes before applying them and undo completed actions.
- **Markdown notes:** edit and preview, search, tags, folders, checklists, and `[[internal links]]`.
- **Tasks and week view:** Kanban boards, due dates, and a weekly reminder.
- **Android calendar:** use calendars already connected on your phone, including Google calendars, through Android's Calendar Provider.
- **Your AI provider:** bring a DeepSeek key or configure another OpenAI-compatible endpoint.

The interface is currently in Russian. This is an early personal project; see [current status and limitations](docs/STATUS.md).

## Install

Download `VibeNotes.apk` from the [latest release](https://github.com/MAppleDm/VibeNotes/releases/latest) and open it on Android 8.0 or newer. The current distribution is a development APK, signed with the project's shared debug key.

Choose a folder for notes and optionally configure your AI provider. Without an API key, inbox messages are saved to `Входящие.md`.

The app can check GitHub for updates every 12 hours; Android asks before installing. Release versions use `vX.X.X`. See [CHANGELOG.md](CHANGELOG.md) and [RELEASING.md](RELEASING.md).

## Data and privacy

Notes are local Markdown files. There is no project account, backend, or telemetry. AI requests go to the provider you configure; API keys are encrypted with Android Keystore. Files excluded by `.aiignore` and all `*.env` files are hidden from the assistant. The chat shows which sources the assistant read.

## Build

Requires JDK 17 and Android SDK 36.

```bash
./gradlew :core:test
python3 tools/check_icu_regex.py core/src/main app/src/main
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. CI tests and builds changes; only version tags publish releases.

## Project layout

| Directory | Purpose |
| --- | --- |
| `core/` | Kotlin/JVM storage, Markdown, Kanban, AI client, and agent logic |
| `app/` | Jetpack Compose UI, Android storage, calendar, and Keystore integration |
| `docs/` | [Architecture](docs/ARCHITECTURE.md) and [implementation status](docs/STATUS.md) |

## Contributing

English and Russian issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). Omit API keys and private notes from reports.

## License

[GPL-3.0](LICENSE). Golos Text is distributed under the [SIL Open Font License 1.1](licenses/GolosText-OFL.txt).
