# Pinned sample Xposed module for local testing

For the Phase 6 hook verification, use a **legacy Xposed API** module that
targets the container's Android version (Android 13 / API 33 in the primary
matrix) and produces an unambiguous, visible effect.

## Choosing the sample

Requirements for the test module:

- Built against the legacy XposedBridge API (or libxposed API) — both are
  supported by the Vector build pinned in AGENT.md §3 (`ddeed8c`).
- Declares `xposedminversion` compatible with the Vector build.
- Hooks something trivially observable (e.g. `Toast.show`, a settings string,
  or an app's about text).

Suggested pin (verify license compatibility, GPL or Apache):

- A minimal module built in-repo for tests is preferred over any third-party
  module: no network fetch, deterministic behavior, GPL-3.0. The test module
  is *not* shipped in the app; it lives only in the test workbench.

## Building the minimal test module

1. Create an empty Android project with a class implementing
   `IXposedHookLoadPackage`.
2. In `handleLoadPackage`, scope to the target package and hook one method
   with a visible side effect (append " [HOOKED]" to a TextView, or log and
   toast).
3. Declare the module metadata (`assets/xposed_init`, `xposedminversion`).
4. Build, then install the module APK **into the container** via the app's
   APK installer (Apps tab), enable it in the Vector manager inside the
   container with scope = target app, restart the target app, and observe.

## Version pinning

The module's `xposedminversion` and target API must match the Vector build
used to deploy the rootfs (recorded in `vector-manifest.json` inside the
rootfs). If you bump the Vector commit, rebuild the module against the new
API level and re-run the runbook.
