# Changelog

All notable changes to **hibernia-framework** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Upgrading straight from a 1.0.x release?** 1.1.0 was published but not widely
> adopted, so moving to `1.2.0` brings **both** the 1.1.0 and 1.2.0 changes. Read both
> sections — especially the ⚠ behaviour-change notes.

## [1.2.0] — Unreleased

Published as `1.2.0-SNAPSHOT` for consumer testing.

### Added
- **On-upgrade defaults reconciliation.** When the plugin version changes, keys that a
  new release ships in its jar defaults but are missing from the operator's on-disk
  files are additively merged in — `config.yml` via a comment-preserving deep merge,
  `messages.properties` via a line-based append. Existing operator values, ordering and
  comments are preserved; a run with no new keys is a no-op. Gated by a
  `.hibernia-version` marker written to the data folder. New `upgrade` package
  (`DefaultsReconciler`, `YamlDefaultsMerger`, `PropertiesDefaultsMerger`); runs first
  in the `HiberniaModule` bootstrap. Opt out with `.withoutDefaultsReconciliation()`.
- **Localized errors from the built-in exceptions.** The semantic exceptions
  (`NoPermissionException`, `BadCommandException`, `NotFoundException`,
  `ConflictException`, `ExceedsLimitException`) now implement `KeyedException` and accept
  a `messages.properties` key plus placeholder pairs — e.g.
  `throw new NotFoundException("myplugin.account.not-found", "name", who)` — resolved
  against the plugin's `Message` bundle in the recipient's locale.
- **`HelpGenerator`** — builds paginated, permission-filtered help directly from the
  registered routes (`CommandManager.routeIndex()` / `RouteInfo`), so `/x help` stays in
  sync with the actual commands instead of being hand-maintained.
- **`BigDecimal` configuration fields.** `@ConfigurationValue` now supports `BigDecimal`,
  read losslessly from the raw scalar — use it for money instead of `double`/`float`.
- **Supertype / interface parameter resolvers.** A resolver registered for a supertype or
  interface now also services a parameter declared as a subtype (cached). Exact matches
  still take precedence.

### Changed
- ⚠ **Config reload is now atomic — read via the accessor.** `ConfigurationLoader.reload()`
  rebuilds each `@ConfigurationComponent` into a fresh instance and swaps the whole
  snapshot in one step instead of mutating live instances in place. This removes the
  config-reload visibility race, but a reference captured at startup (e.g. a Guice-injected
  config singleton) now keeps showing the values it was injected with. **Re-fetch via
  `ConfigurationLoader.getComponent(...)` to observe a reload.**
- **`CommandManager` split** into `RouteBinder`, `CommandTreeBuilder` and `ErrorRenderer`
  collaborators. No behaviour change.
- **Paper API** target bumped `1.21.8` → `1.21.11` to match the consumer plugins.

### Backward compatibility
- Existing `throw new NotFoundException("Some text")` sites are unchanged: a message that
  isn't a defined key falls back to the previous literal rendering.
- The `@ConfigurationValue` field-injection API is unchanged — no consumer recompile is
  required for the atomic-reload change (only the read-after-reload pattern, above).

### Internal / tooling
- SpotBugs added (non-failing) and a JaCoCo coverage gate wired into `check` (excludes the
  Paper-coupled `PaperDialogRenderer`). Gate-effective coverage ≈ 83% line / 70% branch.

## [1.1.0] — 2026-06-21

### Added
- **`HiberniaModule`** — a framework-owned Guice bootstrap that binds the plugin,
  discovered `@ConfigurationComponent`s, `Message`, and multibinder sets for command
  handlers, parameter resolvers, listeners, dialog handlers and input binders, plus the
  dialog renderer and optional Bedrock support. Replaces the hand-rolled per-plugin
  module / commander / configuration wiring.
- **Usher dialog framework** — annotation-driven dialogs over Paper's Dialog API in the
  commander style (`@Dialog`, `@Screen`, `@Action`, `@Input`, `@Model`), with
  `DialogManager` / `DialogView` / `DialogFlow`, built-in input binders, a Paper renderer
  and optional Bedrock detection.
- **i18n overhaul (`Message`):**
  - Per-player locale bundles (`messages_<lang>[_<COUNTRY>].properties`) with a per-key
    fallback chain down to the base bundle.
  - Real `Component` placeholders via a MiniMessage `TagResolver` engine — `ComponentLike`
    values render styled, `Message.rich(...)` is trusted markup, and everything else is
    inert/escaped by default so player input can't inject markup.
  - Optional PlaceholderAPI `%token%` resolution (no-ops when PlaceholderAPI isn't installed).
- Built-in enum `@Input` binding for dialogs.
- Full usage wiki under `docs/`, including PlaceholderAPI setup for consumers.

### Changed
- ⚠ **Fail-loud command routing.** Routes are now validated at registration: a placeholder
  with no matching parameter, a required `@Arg` missing from the route, a required or
  literal segment after an optional one, a non-terminal greedy argument, a duplicate route,
  or an argument-type conflict all throw `IllegalStateException` at registration instead of
  silently dropping the route. A failing handler is skipped and logged; the others still
  register.
- **Exception → message mapping.** The HTTP-semantic exceptions are caught at dispatch and
  rendered to the sender through operator-overridable, translatable `hibernia.error.*` keys,
  with built-in MiniMessage fallbacks.

## [1.0.2] — 2026-06-07

### Fixed
- **Bedrock `@Arg OfflinePlayer` ghost UUIDs (PAR-108).** The resolver now reads from the
  local player cache only and never falls back to the blocking
  `Bukkit.getOfflinePlayer(String)` Mojang lookup, which failed for Floodgate (`.`-prefixed)
  names and fabricated bogus UUIDs that wrote ghost rows. Uncached names are rejected as
  unknown targets instead.

## [1.0.1] — 2026-05-30

### Fixed
- `messages.properties` is no longer clobbered on boot when the operator already has one.
- Optional-argument defaults are converted to the parameter's declared type instead of being
  passed to the handler as the raw annotation string.

## [1.0.0] — 2026-05-23

Initial release (ported from the Business plugin).

### Added
- Guice DI bootstrap and annotation-driven command routing (`@Command`, `@Route`, `@Arg`,
  `@OptionalArg`, `@GreedyArg`, `@Sender`, `@Permission`, `@Async`).
- Built-in parameter resolvers (`String`, `Integer`, `Long`, `BigDecimal`, `Boolean`,
  `OfflinePlayer`), `BigDecimalArgumentType` for monetary command arguments, and a
  primitive→wrapper resolver fallback.
- Reflective configuration injection (`@ConfigurationComponent`, `@ConfigurationValue`) with
  reload support.
- `Message` i18n with MiniMessage formatting (placeholder values escaped by default).
- Domain exception types and `ListenerManager` for DI-managed Bukkit listeners.

[1.2.0]: https://github.com/ParadauxIO/hibernia-framework/compare/v1.1.0...develop
[1.1.0]: https://github.com/ParadauxIO/hibernia-framework/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/ParadauxIO/hibernia-framework/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/ParadauxIO/hibernia-framework/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/ParadauxIO/hibernia-framework/releases/tag/v1.0.0
