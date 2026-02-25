# TASK-001 — Project Setup: Gradle Multi-Module

**Phase**: 0
**Priority**: P0
**Dependencies**: none
**Est. LOC**: ~100 (build scripts, CI config)
**Design refs**: [§8.1 Tech Stack](../design/klaw-design-v0_4.md#81-фреймворк-micronaut), [§8.3 Dependencies]

---

## Summary

Создать базовую структуру проекта: Gradle multi-module monorepo с четырьмя модулями, настроить инструменты качества кода и CI.

---

## Goals

1. Gradle multi-module monorepo: `common`, `gateway`, `engine`, `cli`
2. Kotlin версия и targets:
   - `common`: KMP (jvm + linuxArm64 + linuxX64)
   - `gateway`: JVM (Micronaut)
   - `engine`: JVM (Micronaut)
   - `cli`: Kotlin/Native (linuxArm64 + linuxX64)
3. Минимальные зависимости: kotlinx.serialization, kotlinx.coroutines, kotlinx.datetime
4. Инструменты качества: ktlint, detekt
5. Тестовые зависимости в каждом модуле: kotlin.test, MockK, Testcontainers (engine), WireMock (engine)
6. CI: GitHub Actions — build + test на push

---

## Module Structure

```
klaw/
├── build.gradle.kts              # root: общие версии, плагины, репозитории
├── settings.gradle.kts           # include all modules
├── gradle/
│   └── libs.versions.toml        # version catalog
├── common/                       # KMP: commonMain + jvmMain + nativeMain
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/io/github/klaw/common/
│       ├── jvmMain/kotlin/
│       └── nativeMain/kotlin/
├── gateway/                      # JVM Micronaut app
│   ├── build.gradle.kts
│   └── src/main/kotlin/io/github/klaw/gateway/
├── engine/                       # JVM Micronaut app (includes Scheduler)
│   ├── build.gradle.kts
│   └── src/main/kotlin/io/github/klaw/engine/
└── cli/                          # Kotlin/Native binary
    ├── build.gradle.kts
    └── src/nativeMain/kotlin/io/github/klaw/cli/
```

---

## Key Dependencies (libs.versions.toml)

```toml
[versions]
kotlin = "2.1.x"
kotlinx-serialization = "1.7.x"
kotlinx-coroutines = "1.9.x"
kotlinx-datetime = "0.6.x"
micronaut = "4.x"
micronaut-kotlin = "4.x"
ktor = "3.x"           # for common HTTP types if needed
kaml = "0.x"           # YAML parsing (KMP compatible)
sqldelight = "2.x"     # KMP SQLite
quartz = "2.x"
onnxruntime = "1.17.x"
djl-tokenizers = "0.30.x"
telegram-api = "12.x"  # InsanusMokrassar TelegramBotAPI
clikt = "5.x"          # CLI argument parsing (Native-compatible)
mockk = "1.x"
wiremock = "3.x"
testcontainers = "1.x"
ktlint = "1.x"
detekt = "1.x"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }
kaml = { module = "com.charleskorn.kaml:kaml", version.ref = "kaml" }
# ... etc

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
micronaut-application = { id = "io.micronaut.application", version.ref = "micronaut" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

---

## TDD Approach

На этой фазе нет бизнес-логики, поэтому пишем "дымовые" тесты для каждого модуля, подтверждающие корректность сборки:

1. `common`: тест `1 + 1 == 2` на commonMain (JVM + Native)
2. `gateway`: Micronaut `@MicronautTest` application context test
3. `engine`: Micronaut `@MicronautTest` application context test
4. `cli`: native binary компилируется без ошибок

---

## Acceptance Criteria

- [ ] `./gradlew build` завершается без ошибок
- [ ] `./gradlew test` — все дымовые тесты зелёные
- [ ] `./gradlew common:compileKotlinLinuxArm64` — Native target компилируется
- [ ] `./gradlew ktlintCheck` — нет нарушений стиля
- [ ] `./gradlew detekt` — нет нарушений
- [ ] GitHub Actions workflow: build + test на push в main
- [ ] README.md в корне с инструкциями по сборке

---

## Constraints

- `common` содержит **только** KMP-совместимые зависимости (нет JVM-only библиотек в commonMain)
- `cli` зависит **только** от `common` (Native target) — никаких JVM-зависимостей
- `gateway` и `engine` зависят от `common` (JVM target)
- Не добавлять бизнес-логику в этой задаче

---

## Documentation Subtask

Establish the `doc/` directory skeleton so the engine's `vec_docs` indexing has a valid root to scan at first startup.

**Files to create** (stub content only — filled out by later tasks):

```
doc/
├── index.md
├── tools/         (empty dir — .gitkeep)
├── commands/      (empty dir — .gitkeep)
├── memory/        (empty dir — .gitkeep)
├── scheduling/    (empty dir — .gitkeep)
├── skills/        (empty dir — .gitkeep)
├── config/        (empty dir — .gitkeep)
├── storage/       (empty dir — .gitkeep)
└── workspace/     (empty dir — .gitkeep)
```

`doc/index.md` — minimal stub:
```markdown
# Klaw Documentation

This documentation is written for the Klaw agent itself.

- Use `docs_search` to find information about any capability or parameter.
- Use `docs_list` to browse all available topics.
- Use `docs_read` to read a specific file in full.

Documentation is added incrementally as features are implemented.
```

No other doc content is produced in this task.

---

## Quality Check

После выполнения задачи:
```bash
./gradlew ktlintCheck detekt
# При наличии проблем — исправить, пересобрать
./gradlew build test
```
