# PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-04
**Commit:** def337c
**Branch:** master

## OVERVIEW
MTT (Machine Translation Tool) — Android app that translates text using LLM APIs (OpenAI, Anthropic). Pure Kotlin, Jetpack Compose, Hilt DI, single `:app` module.

## STRUCTURE
```
MTT/
├── app/                          # Single Android module (all source + res)
│   ├── src/main/java/com/mtt/app/
│   │   ├── core/                 # Error hierarchy, logging (only exception to "no logic in core")
│   │   ├── data/                 # All external concerns
│   │   │   ├── cache/            # CacheManager (Room-backed)
│   │   │   ├── io/               # MtoolFileReader/Writer, JsonValidator
│   │   │   ├── llm/              # RateLimiter, TokenEstimator, RetryPolicy, BatchCalculator
│   │   │   ├── local/            # Room AppDatabase, DAOs (Glossary, CacheItem, Project)
│   │   │   ├── model/            # Entities & config DTOs (TranslationConfig, LlmProvider, etc.)
│   │   │   ├── network/          # HttpClientFactory + interceptors (logging, retry)
│   │   │   ├── remote/           # LLM SDK wrappers: OpenAI client, Anthropic client
│   │   │   │   └── llm/          # LlmService interface + factory + provider impls
│   │   │   └── security/         # SecureStorage (EncryptedSharedPreferences)
│   │   ├── di/                   # 6 Hilt modules (App, Database, Network, Repository, Security, UseCase)
│   │   ├── domain/               # Pure business logic — no Android imports
│   │   │   ├── glossary/         # GlossaryEngine (term matching, substitution)
│   │   │   ├── pipeline/         # TranslationExecutor (orchestrates full pipeline)
│   │   │   ├── prompt/           # PromptBuilder (translate/polish/proofread prompts)
│   │   │   ├── usecase/          # Glossary extraction: ExtractTermsUseCase (两阶段AI提取)
│   │   │   │   # Stage 1: LLM direct extraction from text chunks (3 categories)
│   │   │   │   # Stage 2: AI dedup/merge with weighted rescue fallback
│   │   │   │   # Replace old frequency-analysis approach; reference: AiNiee AnalysisTask.py
│   │   ├── service/              # TranslationService (foreground, dataSync type)
│   │   └── ui/                   # Compose screens + ViewModels (MVVM)
│   │       ├── translation/      # Main translate screen
│   │       ├── glossary/         # Terminology management
│   │       ├── settings/         # API keys, model selection
│   │       ├── result/           # Translation results viewer
│   │       └── theme/            # Material 3 theme (Color, Type, Shape)
│   └── src/test/                 # 31+ unit tests (JVM-only, Robolectric)
├── gradle/                       # Wrapper (Gradle 8.9)
├── build.gradle.kts              # Root: plugin declarations only
├── settings.gradle.kts           # Single module: include(":app")
└── AiNiee-ref/                   # ⚠️ Reference Python project — NOT part of Android codebase (gitignored)
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Adding a new LLM provider | `data/remote/` + `data/remote/llm/LlmServiceFactory.kt` | Implement LlmService, wire factory |
| Changing translation pipeline | `domain/pipeline/TranslationExecutor.kt` | 9-step pipeline: preprocess→prompt→estimate→rate-limit→call→validate→extract→rejoin |
| Glossary extraction (AI) | `domain/usecase/ExtractTermsUseCase.kt` | Two-stage: LLM direct extraction → AI dedup/merge. Reference: AiNiee AnalysisTask.py |
| Adding a screen | `ui/{feature}/` | `{Feature}Screen.kt` + `{Feature}ViewModel.kt`; register in `AppNavHost.kt` |
| Adding a database table | `data/model/` + `data/local/dao/` + `data/local/AppDatabase.kt` | Entity → DAO → bump DB version in Migration.kt |
| Changing API key storage | `data/security/SecureStorage.kt` | Uses `EncryptedSharedPreferences` |
| Adding a DI dependency | `di/{Module}.kt` | One module per concern (App, Database, Network, etc.) |
| Rate limiting logic | `data/llm/RateLimiter.kt` | Semaphore-based RPM/TPM sliding window |
| Prompt templates | `domain/prompt/PromptBuilder.kt` | 3 modes: translate, polish, proofread |
| Navigation | `AppNavHost.kt` | Bottom-nav with 3 tabs + result screen |

## CONVENTIONS
- **Naming**: `{Feature}Screen` / `{Feature}ViewModel` for UI, `{Entity}Entity` for Room models, `{Feature}Dao` for DAOs, `{Verb}{Noun}UseCase` for use cases
- **Error handling**: All exceptions extend `MttException` (sealed). Use `Result<T>` sealed interface (copy of Kotlin's Result with extensions).
- **DI**: `@Inject constructor` on every class. `@Provides @Singleton` in Hilt modules for external deps.
- **Logging**: Always through `AppLogger.d/i/w/e(tag, msg)`. NEVER log with `Timber` directly. Logcat in debug, file (5MB rotate) in release.
- **Coroutines**: All suspend functions. ViewModels use `StandardTestDispatcher` in tests.
- **Testing**: `mockk()` (NOT Mockito). `coEvery/coVerify` for suspend. `MockWebServer` for HTTP. `FakeLlmService` for E2E.
- **Dependency versions**: Inline in `app/build.gradle.kts` (no version catalog). Compose BOM `2024.06.00` handles Compose versions.
- **Kotlin DSL**: All `.gradle` files are `.kts`.

## ANTI-PATTERNS (THIS PROJECT)
- **NEVER** log API keys, source text, or LLM responses (enforced in `AppLogger`, `LoggingInterceptor`)
- **NEVER** use `Timber` directly — always `AppLogger`
- **NEVER** `@Suppress("UNCHECKED_CAST")` outside `Result.kt` (3 existing cases are necessary for type-erased sealed transforms)
- **DO NOT** add Android imports to `domain/` — it must remain pure Kotlin
- **DO NOT** annotate `SecureStorage` with `@Inject` (DI conflict — use module binding)
- **DO NOT** modify `AiNiee-ref/` — it's the Python reference, gitignored

## UNIQUE STYLES
- **`Result<T>` monad**: Custom sealed interface with `map`, `flatMap`, `onSuccess`, `onFailure`, `getOrNull`, `getOrThrow`, `getOrDefault`. Used throughout `domain/` and `data/`.
- **Pipeline with split-and-retry**: `TranslationExecutor.orchestrate()` processes chunks in a queue. On validation failure, splits chunk 50/50 and retries (max 3). Accepts partial results as best-effort fallback.
- **Two-stage glossary extraction**: `ExtractTermsUseCase` uses AiNiee-style two-stage LLM pipeline — Stage 1 sends raw text to LLM with few-shot prompt (no frequency analysis), Stage 2 dedups/merges via AI with weighted rescue fallback. Three categories: character/term/non_translate.
- **Dummy client pattern**: `TranslationExecutor.createLlmService()` creates a "dummy" client for the unused provider — factory only uses the matching one.
- **Shared static state**: `TranslationService.companion` exposes `pendingTexts`, `pendingConfig`, and `_serviceProgress` as static vars for ViewModel ↔ Service communication.
- **Debug auto-config**: `/data/local/tmp/mtt_debug_config.json` configures API keys, model, and test JSON path on first launch for emulator testing.
- **Debug extraction intent**: `adb shell am startservice -n com.mtt.app/.service.TranslationService -a com.mtt.app.action.DEBUG_EXTRACT --es path /path/to/file.json` triggers glossary extraction from CLI (requires app in foreground on Android 12+).
- **Kotlin backtick test names**: `fun \`translate when 401 error then throws ApiException\`()`

## GRADLE MCP (MANDATORY)

**ALL Gradle operations MUST use the Gradle MCP tools. NEVER use raw `./gradlew` shell commands.**

If Gradle MCP is not installed, install it from: https://github.com/rnett/gradle-mcp/blob/main/README.md

### Gradle MCP Tools:
| Tool | Purpose |
|------|---------|
| `gradle_gradle` | Execute Gradle builds/tasks |
| `gradle_inspect_build` | Check build status, test failures, task outputs |
| `gradle_lookup_maven_versions` | Check Maven dependency versions |
| `gradle_inspect_dependencies` | Inspect dependency graph, check for updates |
| `gradle_gradle_docs` | Read official Gradle docs for project version |

### Build Commands (via Gradle MCP):
```kotlin
// Build
gradle_gradle(projectRoot="<project-root>", commandLine=[":app:assembleDebug"])

// Run all unit tests
gradle_gradle(projectRoot="<project-root>", commandLine=[":app:testDebugUnitTest"])

// Run specific test
gradle_gradle(projectRoot="<project-root>", commandLine=[":app:testDebugUnitTest", "--tests", "com.mtt.app.domain.pipeline.TranslationExecutorTest"])

// Lint
gradle_gradle(projectRoot="<project-root>", commandLine=[":app:lintDebug"])

// Install on device (adb only - no Gradle task for adb)
// gradle_gradle installDebug then adb install
```

### Check build status:
```kotlin
gradle_inspect_build(projectRoot="<project-root>", mode="summary", timeout=120, waitForFinished=true)
```

> Note: `projectRoot` is auto-detected from the workspace and typically doesn't need to be explicitly set.

### Tool Reference:
| Tool | Purpose |
|------|---------|
| `gradle_gradle` | Execute Gradle builds/tasks |
| `gradle_inspect_build` | Check build status, test failures, task outputs |
| `gradle_lookup_maven_versions` | Check Maven dependency versions |
| `gradle_inspect_dependencies` | Inspect dependency graph, check for updates |
| `gradle_gradle_docs` | Read official Gradle docs for project version |

## NOTES
- **AiNiee-ref/**: Python reference implementation of the translation pipeline. Not part of the Android codebase.
- The app uses `EncryptedSharedPreferences` for API keys but `isMinifyEnabled = false` — no R8 obfuscation in release.
- LLM SDK JARs (`openai-java`, `anthropic-java`) exclude SLF4J and GSON to avoid transient dependency conflicts.
- `RateLimiter` uses `Semaphore` with sliding-window timestamps, not Android's token bucket. Timeout is 60s.
- `HiltTestRunner` extends `RobolectricTestRunner` — required for any test needing Hilt injection.
- **Debug auto-config**: Place `/data/local/tmp/mtt_debug_config.json` before first launch to auto-configure API keys and pre-load a test JSON file. Format: `{"openai_api_key":"...","openai_base_url":"...","openai_model":"...","test_json_path":"/path/to/file.json"}`. Consumed once — reinstall to re-consume.
- **Glossary extraction (adb)**: `adb shell am startservice -n com.mtt.app/.service.TranslationService -a com.mtt.app.action.DEBUG_EXTRACT --es path /sdcard/Download/ManualTransFile.json` runs two-stage AI extraction from a JSON file. App must be in foreground (Android 12+ restriction). Results logged to logcat with tag `TranslationService`.
- **Glossary extraction (UI)**: Go to Glossary tab → tap "从原文提取术语 (AI)". Button is enabled when source texts are loaded (from Translation tab).
