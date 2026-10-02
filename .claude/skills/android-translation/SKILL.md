---
name: android-translation
description: Add or complete a language (e.g. German) in an Android app — move hard-coded UI text into string resources, create values-<lang>/strings.xml, handle placeholders, plurals and escaping, and verify with lint. Use when the user wants the app "auf Deutsch", reports untranslated text, or when adding any new user-visible string (every new string needs all translations).
---

# Translating an Android app

## Workflow

1. **Inventory:** `ls app/src/main/res/ | grep values` and compare keys per language:
   ```bash
   for f in app/src/main/res/values*/strings*.xml; do echo "$f $(grep -c '<string ' "$f")"; done
   ```
2. **Hard-coded text:** search layouts and code, then move it into resources:
   ```bash
   grep -rn 'android:text="[^@]' app/src/main/res/layout
   grep -rn 'android:contentDescription="[^@]\|android:hint="[^@]' app/src/main/res/layout
   grep -rnE 'Toast.makeText\([^,]+, *"|setTitle\("|\.text = "' app/src/main/java
   ```
   Large sets can live in extra files (`strings_layouts.xml`, `strings_code.xml`) with the same
   file name in each `values-<lang>/`.
3. **Translate** into `values-de/strings.xml` (same keys). Keep `%1$s`/`%d` placeholders and
   their order index; escape `'` as `\'`, `"` as `\"`, `&` as `&amp;`, `@`/`?` at the start with `\`.
   Use `<plurals>` for counts. Do not translate `translatable="false"` keys.
4. **App label and system texts:** the launcher label, accessibility service description,
   notification channels and tile labels must reference resources too (manifest `@string/...`).
5. **Limit bundled languages** to supported ones: `defaultConfig { resConfigs("en", "de") }`
   (`androidResources.localeFilters` on newer AGP) — library strings in other languages are dropped.
6. **Verify:**
   ```bash
   ./gradlew lintDebug   # MissingTranslation, ExtraTranslation, StringFormatMatches
   ```
   and a Robolectric smoke test with `@Config(qualifiers = "de")` if layouts depend on text length.

## In-app language switch (if the app has one)

Override only the locale when wrapping the context (`createConfigurationContext` with a copy of
the current configuration where just `setLocale` changes). Copying a full stale `Configuration`
froze orientation and other values in a real project.

## Style for German

Formal "Sie" or informal "du" — ask once or follow existing texts; keep it consistent. Prefer
short labels (sidebars and buttons are narrow), use Android's own German terms
("Bedienungshilfen", "Benachrichtigungen", "Bildschirm teilen", "Extradunkel").
