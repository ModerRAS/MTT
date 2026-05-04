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
│   │   │   └── usecase/          # TranslateTextsUseCase
│   │   ├── service/              # TranslationService (foreground, dataSync type)
│   │   └── ui/                   # Compose screens + ViewModels (MVVM)
│   │       ├── translation/      # Main translate screen
│   │       ├── glossary/         # Terminology management
│   │       ├── settings/         # API keys, model selection
│   │       ├── result/           # Translation results viewer
│   │       └── theme/            # Material 3 theme (Color, Type, Shape)
│   └── src/test/                 # 28 unit tests (JVM-only, Robolectric)
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
- **Dummy client pattern**: `TranslationExecutor.createLlmService()` creates a "dummy" client for the unused provider — factory only uses the matching one.
- **Shared static state**: `TranslationService.companion` exposes `pendingTexts`, `pendingConfig`, and `_serviceProgress` as static vars for ViewModel ↔ Service communication.
- **Kotlin backtick test names**: `fun \`translate when 401 error then throws ApiException\`()`

## COMMANDS
```bash
# Build (Windows)
.\gradlew.bat assembleDebug

# Run all unit tests
.\gradlew.bat testDebugUnitTest

# Run specific test
.\gradlew.bat testDebugUnitTest --tests "com.mtt.app.domain.pipeline.TranslationExecutorTest"

# Lint
.\gradlew.bat lintDebug

# Install on device
.\gradlew.bat installDebug
```

## NOTES
- **AiNiee-ref/**: Python reference implementation of the translation pipeline. Not part of the Android codebase.
- The app uses `EncryptedSharedPreferences` for API keys but `isMinifyEnabled = false` — no R8 obfuscation in release.
- LLM SDK JARs (`openai-java`, `anthropic-java`) exclude SLF4J and GSON to avoid transient dependency conflicts.
- `RateLimiter` uses `Semaphore` with sliding-window timestamps, not Android's token bucket. Timeout is 60s.
- `HiltTestRunner` extends `RobolectricTestRunner` — required for any test needing Hilt injection.
