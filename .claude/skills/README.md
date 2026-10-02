# Claude Code skills for this repository

Project skills Claude Code loads automatically in sessions on this repo (`.claude/skills/*/SKILL.md`).
They capture what was needed to develop, build and ship this Android app from a cloud session.

| Skill | Purpose |
|---|---|
| `android-cloud-setup` | Install Android SDK in the container, Maven mirror on HTTP 429, shallow-clone check |
| `android-apk-update-build` | APK that installs as an update: commit-count versionCode, fixed dev key, verification |
| `android-robolectric-tests` | Unit tests for views/gestures/prefs without emulator; known traps |
| `android-github-ci` | GitHub Actions build, signed artifact, monitoring via GitHub MCP tools |
| `android-translation` | Adding German (or another language), lint checks |
| `android-platform-pitfalls` | Android 10–17 restrictions and fallbacks for system-near features |

Scripts in `*/scripts/` are helpers: review them before running them in other environments.
