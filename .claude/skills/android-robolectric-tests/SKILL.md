---
name: android-robolectric-tests
description: Write and run JVM unit tests for Android views, services helpers and SharedPreferences logic with Robolectric when no emulator or device is available (cloud containers, CI). Use when adding tests for touch gestures, RecyclerView layouts, preference storage or clipboard logic, or when Robolectric tests fail, hang or download nothing. German triggers: "Tests schreiben", "kannst du das testen".
---

# Robolectric tests without a device

## Setup (app/build.gradle.kts)

```kotlin
android { testOptions { unitTests {
    isIncludeAndroidResources = true
    all { it.testLogging { events("started", "passed", "failed", "skipped") } }
} } }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
```

Run: `./gradlew testDebugUnitTest` (single class: `--tests '*SidePanelViewTest'`).
Reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

## Test class template

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FooTest {
    private lateinit var context: Context
    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("prefs_name", Context.MODE_PRIVATE).edit().clear().commit()
        context = ContextThemeWrapper(app, R.style.AppTheme)   // needed for Material views
    }
}
```

## Lessons learned

- **Plain `Application`:** the app's own `Application` class may do things Robolectric cannot
  (e.g. HiddenApiBypass, Shizuku, root checks). `@Config(application = android.app.Application::class)` avoids that.
- **Pin `sdk`** to the targetSdk; Robolectric downloads one android-all jar per SDK level
  (via Maven Central → use the mirror from `android-cloud-setup` on 429).
- **Frozen clock:** `SystemClock`/timestamps do not advance. Logic that dedups by time needs
  `shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))` between steps.
- **Touch gestures:** build sequences with `MotionEvent.obtain(downTime, eventTime, action, x, y, 0)`,
  call `measure()`+`layout()` on the view first, then `dispatchTouchEvent`, then idle the looper so
  posted runnables (tap timeouts, long press) run. Test single/double tap and "interrupted" sequences.
- **Clear SharedPreferences in `@Before`** — Robolectric keeps them per test run otherwise.
- **Test hooks over mocking:** for time or system state, an `internal var clock: (() -> Int)? = null`
  in the object is simpler than mocking frameworks.
- **Never let tests hang:** code that spawns processes (`su`, `sh`) can block forever on CI runners
  (GitHub's runner *has* `su`). Give every `Process.waitFor` a timeout and close stdin. Reproduce by
  putting a fake hanging `su` first on `PATH`.
- Robolectric cannot verify real system behavior (overlays over other apps, accessibility,
  split screen, hidden APIs). State that limitation and ask the user to test on the device.
