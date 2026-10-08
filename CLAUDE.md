# CLAUDE.md – Pusslet

Familjens vardagspussel: a Kotlin Multiplatform (Android first, iOS compiles) family planner for Swedish families.
The full product brief is in [docs/BRIEF.md](docs/BRIEF.md) – read it before larger changes. Decisions are logged in
[docs/DECISIONS.md](docs/DECISIONS.md), status per phase in [docs/PROGRESS.md](docs/PROGRESS.md), owner steps in
[docs/SETUP.md](docs/SETUP.md), design tokens and tone in [docs/DESIGN.md](docs/DESIGN.md).

## Hard rules (from the owner)
- **No AI / LLM services.** No external AI APIs, no AI keys anywhere. All "smart" features are rule based and local (ADR-001).
- **No hardcoded UI strings.** Swedish is the default (`values/`), English in `values-en/`. Strings live in
  `shared/ui/src/commonMain/composeResources/values*/strings.xml` and are used via `se.pusslet.ui.resources.Res`.
- Offline first: Room is the source of truth; network/Firebase only mirrors it.
- Children's data is minimal. Allergies/diet are opt-in sensitive data. Never log personal data.
- No `!!` in production code without a comment, no silent `catch`, no scraping of grocery sites.
- Never push to a remote unless the owner asks. Conventional Commits (`feat(shopping): …`, `fix(calendar): …`).
- Week starts Monday, ISO week numbers shown, 24h clock, `Europe/Stockholm` default zone (always pass a `TimeZone`).

## Modules
| Module | Contents |
|---|---|
| `composeApp` | **Android application** (flavors `demo`/`dev`/`prod`), MainActivity, widgets (Glance), WorkManager, CalendarContract, CameraX/ML Kit, FCM. Named `composeApp` so `:composeApp:assembleDemoDebug` works (AGP 9 forbids KMP + application in one module, ADR-004). |
| `shared/core` | Pure Kotlin domain: models, parsers (Swedish quick-add, ICS/RRULE), dates/weeks, holidays, units, shopping merge, clothing rules, custody, conflicts. Heavily unit tested. |
| `shared/data` | Room database, repositories, sync engine, Ktor adapters for open data, Firebase (GitLive Auth/Functions; native Firestore on Android). |
| `shared/ui` | Design system: theme, member colours, components, icons and **all string resources**. |
| `shared/feature-*` | today, calendar, meals, shopping, tasks, family (incl. onboarding), settings – Compose UI + ViewModels. |
| `shared/app` | App shell: navigation (Navigation 3), scaffold, Koin graph aggregation, iOS `MainViewController`. |
| `functions/` | Firebase Cloud Functions (TypeScript) + Firestore rules tests. |
| `build-logic/` | Convention plugins: `pusslet.kmp.library`, `pusslet.kmp.compose`, `pusslet.kmp.feature`, `pusslet.quality`. |

Android namespace of a shared module is derived from its path (`:shared:feature-today` → `se.pusslet.feature.today`).

## Commands
Use Git Bash (or any POSIX shell) from the repo root. JDK: Android Studio's bundled JBR (JDK 25) works.
```bash
./gradlew :composeApp:assembleDemoDebug          # build the demo app (no Firebase keys needed)
./gradlew :composeApp:installDemoDebug           # install on a running emulator/device
./gradlew testAndroidHostTest :composeApp:testDemoDebugUnitTest   # all JVM unit tests
./gradlew :shared:core:testAndroidHostTest       # core domain tests only (fast)
./gradlew ktlintCheck detekt                     # lint (ktlintFormat to auto-fix)
./gradlew build                                  # everything incl. lint, tests, all variants
./gradlew :composeApp:bundleProdRelease          # release AAB (needs signing config, see docs/SETUP.md)
./gradlew :composeApp:recordRoborazziDemoDebug   # re-record screenshot goldens
cd functions && npm ci && npm test               # Firestore rules + functions tests (starts emulators)
firebase emulators:start --project demo-pusslet  # local backend for the demo flavor
```
iOS targets only compile on macOS (`./gradlew :shared:app:linkDebugFrameworkIosSimulatorArm64`); CI has a macOS job.

### Local machine quirk (this Windows dev box)
JDK AF_UNIX sockets fail in `%TEMP%` here, which breaks the Gradle daemon ("Unable to establish loopback connection").
Workaround: `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\Users\ermhad001\.gradle\udtmp"` and
`export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` before running `./gradlew`.

## Conventions
- Kotlin official style, ktlint + detekt (config in `.editorconfig`, `config/detekt/detekt.yml`). Max line 140.
- Time: `kotlin.time.Instant`/`Clock`, `kotlinx.datetime` `LocalDate`/`LocalTime`. Inject `Clock` and `TimeZone`; never call `Clock.System` inside domain logic.
- IDs: `kotlin.uuid.Uuid.random().toString()` via `IdGenerator` (injectable for tests).
- ViewModels expose `StateFlow<UiState>`; UI is stateless composables + a thin `*Route` composable that wires the ViewModel.
- Every external source sits behind an interface + adapter, caches in Room with a `fetchedAt`, and must fail soft.
- Tests: domain in `shared/core/src/commonTest`, fixtures in `shared/data/src/commonTest/resources/fixtures/`.
- Logging: Kermit `Logger`, never log names, e-mails, addresses, allergies or free text typed by users.
