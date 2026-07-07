# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- `AirStrings.create()` no longer runs the local bundle load (seed asset I/O, JSON decode, Ed25519 verification, and the disk write when the seed wins) synchronously on the caller thread. Local candidates now load on `Dispatchers.IO` inside the SDK's coroutine scope, per the bundled-fallback contract's "seeding never blocks app startup" guarantee. Ordering is unchanged: local candidates are still applied before the first network refresh result, so anti-downgrade semantics are preserved.

## [0.4.0] - 2026-06-10

### Added

- Bundled fallback (seed) support per the platform `bundled-fallback.md` contract: published, signed bundles shipped in `assets/airstrings/bundles/` are loaded on startup and on `setLocale`, fully Ed25519-verified, and compete with the cached bundle — highest revision wins, ties go to the cache. A winning seed is persisted through the normal cache path.
- `AirStringsConfiguration.seedEnabled` (default `true`) to disable seeding and `AirStringsConfiguration.seedDirectory` (default `"airstrings/bundles"`) to override the seed asset path. Additive only — existing integrations compile unchanged.
- `AirStringsError.SeedProjectMismatch` and `AirStringsError.SeedLocaleMismatch` for seed bundles whose `project_id` or `locale` does not match the configuration or requested locale. Invalid seeds are rejected as hard errors (logged, never served, never cached); a missing seed directory or asset is a silent no-op.

## [0.3.5] - 2026-05-16

Baseline release before this changelog was introduced: fetch, verify (Ed25519), cache, and serve signed string bundles; ICU MessageFormat support; locale switching; ETag conditional requests; foreground refresh; Jetpack Compose integration. See git history for details.
