# AGENTS.md — HiberniaFramework

## Project Overview

Java 21 library providing a shared framework for PaperMC Minecraft plugins. Built with
Gradle 8.14 (Kotlin DSL). Provides Guice-based DI bootstrap, annotation-driven command
routing, reflective configuration injection, i18n, and domain exception types.

- **Group/Artifact:** `io.paradaux:hibernia-framework`
- **License:** AGPL-3.0-or-later
- **Source root:** `src/main/java/io/paradaux/hibernia/framework/`

## Build Commands

All commands use the Gradle wrapper. On Windows use `gradlew.bat`, on Unix use `./gradlew`.

```bash
# Full build (compile + test + assemble JAR)
./gradlew build

# Compile only (no tests/JAR)
./gradlew compileJava

# Clean build artifacts
./gradlew clean

# Fat/shaded JAR (classifier: "shaded")
./gradlew shadowJar

# Publish to Reposilite Maven repo (requires REPO_USER/REPO_PASS env vars)
./gradlew publish
```

## Test Commands

JUnit 5 + Mockito, with JaCoCo coverage reports (XML + HTML under
`build/reports/jacoco/`). The suite covers the commander (registration
validation, dispatch, exception mapping), configurator, i18n, resolvers,
exceptions, utils and the HiberniaModule bootstrap.

```bash
# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "io.paradaux.hibernia.framework.utils.StringUtilsTest"

# Run a single test method
./gradlew test --tests "io.paradaux.hibernia.framework.utils.StringUtilsTest.testSanitize"

# Run tests matching a pattern
./gradlew test --tests "*StringUtils*"
```

> Gradle 8.14 cannot run on Java 25. If the system `java` is newer than 21,
> pass `-Dorg.gradle.java.home=<path-to-jdk-21>` (e.g. `~/.jdks/jdk-21.0.11`).

## Linting / Static Analysis

SpotBugs is configured (`com.github.spotbugs` Gradle plugin) and runs as part of
`check`, currently **non-failing** (`spotbugs.ignoreFailures = true`) — the value is
the HTML report under `build/reports/spotbugs/`; ratchet to fail-on-new once findings
are triaged. No Checkstyle/PMD/Error Prone. JaCoCo enforces a coverage gate via
`jacocoTestCoverageVerification` (wired into `check`); the Paper-coupled
`usher/render/PaperDialogRenderer` is the one documented exclusion.

## CI/CD

GitHub Actions workflow at `.github/workflows/publish.yml`:
- Triggers on push to `main` (release) or `develop` (snapshot), plus manual dispatch
- Runs: `./gradlew --no-daemon clean build publish -Pversion=<computed>`
- JDK: Temurin 21 with Gradle caching

## Project Structure

```
src/main/java/io/paradaux/hibernia/framework/
├── commander/                 # Command framework core
│   ├── CommandManager.java    # Main orchestrator (validation, dispatch, error mapping)
│   ├── RouteInfo.java         # Public route metadata record (for help generation)
│   ├── annotations/           # @Command, @Route, @Arg, @OptionalArg, @Sender, @Permission, @Async, etc.
│   ├── arguments/             # Paper CustomArgumentType extensions (BigDecimalArgumentType)
│   ├── resolvers/             # BigDecimal, Boolean, Integer, Long, OfflinePlayer, String resolvers
│   └── spi/                   # CommandHandler, ParameterResolver<T>
├── configurator/              # Annotation-based config injection (+ in-place reload())
│   ├── ConfigurationLoader.java
│   ├── ConfigurationProcessor.java
│   └── annotations/           # @ConfigurationComponent, @ConfigurationValue
├── events/                    # ListenerManager — DI-managed Bukkit listener registration
├── exceptions/                # Unchecked domain exceptions (6 classes, mapped to hibernia.error.* messages)
├── guice/                     # HiberniaModule — framework-owned Guice bootstrap module
├── i18n/                      # Message.java — templated messaging with MiniMessage (values escaped by default)
├── models/                    # HiberniaPlayer interface
├── usher/                     # Dialog framework over Paper's Dialog API (commander-style)
│   ├── DialogManager.java     # orchestrator: index handlers, open flows, route clicks, error mapping
│   ├── DialogView.java        # renderer-agnostic screen spec (fluent builder, fully unit-tested)
│   ├── DialogFlow.java        # per-viewer navigation back-stack + async await()
│   ├── DialogContext.java / Text.java / ButtonSpec.java
│   ├── annotations/           # @Dialog, @Screen, @Action, @Input, @Model
│   ├── input/                 # DialogInputSpec (text/bool/toggle/option/number)
│   ├── binders/               # BuiltinInputBinders (String/Boolean/Integer/Long/Float/Double)
│   ├── render/                # DialogRenderer iface + PaperDialogRenderer (the only Paper-coupled class)
│   └── spi/                   # DialogHandler, InputBinder<T>, BedrockSupport
└── utils/                     # StringUtils
```

## Key Dependencies

- `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` (compileOnly)
- `com.google.inject:guice:7.0.0` (DI — `api` scope: HiberniaModule/CommandManager expose Guice types)
- `com.google.guava:guava:33.2.1-jre`
- `org.reflections:reflections:0.10.2` (classpath scanning)
- `org.projectlombok:lombok:1.18.34` (compileOnly + annotation processor)
- Shadow plugin is `com.gradleup.shadow` 9.x (shadowJar keeps the `shaded` classifier; the thin jar stays the published artifact)

## Code Style Guidelines

### Formatting
- **Indentation:** 4 spaces, no tabs
- **Braces:** K&R style (opening brace on same line)
- **Line length:** ~120 characters soft limit
- **Single-line guards:** Allowed without braces (e.g., `if (x == null) continue;`)
- **Blank lines:** One blank line between methods; no excessive blanks within methods

### Imports
- **Two groups separated by a blank line:**
  1. Third-party and framework imports (`com.*`, `io.*`, `net.*`, `org.*`, `lombok.*`)
  2. Java standard library (`java.*`)
- Wildcard imports used selectively for closely related annotation groups
- All imports are fully qualified (absolute)

### Naming Conventions
| Element        | Convention         | Examples                                    |
|----------------|--------------------|---------------------------------------------|
| Classes        | PascalCase         | `CommandManager`, `ConfigurationProcessor`  |
| Interfaces     | PascalCase (no I)  | `CommandHandler`, `ParameterResolver<T>`    |
| Annotations    | PascalCase         | `@Command`, `@Route`, `@ConfigurationValue` |
| Methods        | camelCase          | `registerAll()`, `buildCommandTree()`       |
| Variables      | camelCase          | `classPerm`, `rootBuilder`, `matchingParam` |
| Constants      | UPPER_SNAKE_CASE   | `PLACEHOLDER_PREFIX`, `MM_TAG_REGEX`        |
| Packages       | lowercase          | `commander`, `annotations`, `resolvers`     |
| Exceptions     | PascalCase+Exception | `NotFoundException`, `ConflictException`  |

### Types and Generics
- Interfaces preferred over abstract classes for contracts
- Java records for internal value objects (e.g., `Segment`, `Param` in `CommandManager`)
- `Optional<T>` as return type only — never as a field type
- Bounded wildcards used idiomatically (`Map<Class<?>, ParameterResolver<?>>`)
- `@SuppressWarnings("unchecked")` applied locally when unavoidable casts are needed

### Error Handling
- Custom exceptions extend `RuntimeException` (unchecked), named after HTTP semantics:
  `BadCommandException`, `ConflictException`, `ExceedsLimitException`,
  `InternalException`, `NoPermissionException`, `NotFoundException`
- Try/catch: catch specific exceptions first (`InvocationTargetException`), then broad
  `Exception` as fallback
- User-facing error messages sent to `CommandSender`; stack traces logged server-side
- Try-with-resources for I/O streams

### Dependency Injection
- Google Guice with `@Inject` constructor injection (never field injection)
- `@Singleton` scope on service classes (`CommandManager`, `Message`, `ConfigurationLoader`)
- Guice multibinding via `Set<T>` constructor parameters for extensible registries

### Logging
- Lombok `@Slf4j` annotation preferred — generates `log` field
- Use `log.error(...)`, `log.warn(...)`, etc.
- Fallback: `plugin.getLogger().warning(...)` in classes without Lombok

### Visibility
- Fields: `private final` for dependencies and config; `private static final` for constants
- Methods: `public` for API surface only; `private` for internal implementation
- No `protected` methods — this project does not use inheritance hierarchies
- Inner types are `private` (records, static classes)

### Documentation
- Javadoc on all public API: classes, interfaces, annotations, public methods
- Use `<p>` for paragraphs, `<pre>` for code examples, `@param`/`@return`/`@throws` tags
- Inline `//` comments used sparingly for short explanations
- Avoid commented-out code

### Functional Patterns
- Lambdas and streams used freely for iteration, filtering, mapping
- `@FunctionalInterface` on single-method interfaces (e.g., `SuggestionProvider`)
- `var` used for local variables when the type is obvious from context

### File Organization
- One public type per file, filename matches class name
- Class member ordering: static constants → instance fields → constructor → public
  methods → private methods → inner types (records/classes) at bottom
- Strictly one `src/main/java` source root; no multi-module structure
