# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.1] - 2026-07-15

### Changed

- Document that `onExposure` dedupes once per session (not per screen view).

## [1.1.0] - 2026-07-14

### Added

- String Variants (A/B testing). `setAssignmentId(id)` selects experiment variants for a stable assignment id; `onExposure` (with the new `ExposureEvent` public data class) fires once per `(key, experiment, variant, assignment)` on first read, delivered asynchronously on the main thread, so you can forward exposures to analytics. Experiment metadata is covered by a separate `experiments_signature`, verified independently of the base bundle signature; a missing or invalid experiments signature soft-fails to base values while the bundle itself is still served. Selection is stateless and deterministic (SHA-256 bucketing), matching the iOS and web SDKs. Additive only — existing integrations compile and behave unchanged.

First stable release. The public API is now frozen under Semantic Versioning: no breaking change ships without a major (2.0.0) bump. See the SDK stability and deprecation policy in `docs/contracts/sdk-requirements.md`.

### Fixed

- `AirStrings.create()` no longer runs the local bundle load (seed asset I/O, JSON decode, Ed25519 verification, and the disk write when the seed wins) synchronously on the caller thread. Local candidates now load on `Dispatchers.IO` inside the SDK's coroutine scope, per the bundled-fallback contract's "seeding never blocks app startup" guarantee. Ordering is unchanged: local candidates are still applied before the first network refresh result, so anti-downgrade semantics are preserved.

## [0.4.0] - 2026-06-10

### Added

- Bundled fallback (seed) support per the platform `bundled-fallback.md` contract: published, signed bundles shipped in `assets/airstrings/bundles/` are loaded on startup and on `setLocale`, fully Ed25519-verified, and compete with the cached bundle — highest revision wins, ties go to the cache. A winning seed is persisted through the normal cache path.
- `AirStringsConfiguration.seedEnabled` (default `true`) to disable seeding and `AirStringsConfiguration.seedDirectory` (default `"airstrings/bundles"`) to override the seed asset path. Additive only — existing integrations compile unchanged.
- `AirStringsError.SeedProjectMismatch` and `AirStringsError.SeedLocaleMismatch` for seed bundles whose `project_id` or `locale` does not match the configuration or requested locale. Invalid seeds are rejected as hard errors (logged, never served, never cached); a missing seed directory or asset is a silent no-op.

## [0.3.5] - 2026-05-16

Baseline release before this changelog was introduced: fetch, verify (Ed25519), cache, and serve signed string bundles; ICU MessageFormat support; locale switching; ETag conditional requests; foreground refresh; Jetpack Compose integration. See git history for details.
