# Claude Code skills for this repository

Project skills Claude Code loads automatically in sessions on this repo (`.claude/skills/*/SKILL.md`).
They capture what was needed to develop, build, secure and ship Android apps from cloud sessions.
Hui-161/Smart-Edge and Hui-161/essentials_app keep identical copies: after improving a skill in one
repository, copy `.claude/skills/` to the other.

| Skill | Purpose |
|---|---|
| `android-cloud-setup` | Android SDK in the container, Maven mirror on HTTP 429, Gradle/JDK download workarounds, wrapper check, shallow-clone check |
| `android-apk-update-build` | APK that installs as an update: commit-count versionCode, fixed dev key, verification, release signing and updater check |
| `android-robolectric-tests` | Unit tests for views/gestures/prefs without emulator; known traps |
| `android-github-ci` | GitHub Actions build, signed artifact, release workflow, forks, monitoring via GitHub MCP tools |
| `android-translation` | Adding German (or another language), lint checks |
| `android-platform-pitfalls` | Android 10–17 restrictions and fallbacks for system-near features |
| `android-security-review` | Security/privacy review of apps with powerful permissions: attack-surface inventory, known patterns and fixes, report |

Scripts in `*/scripts/` are helpers: review them before running them in other environments.
