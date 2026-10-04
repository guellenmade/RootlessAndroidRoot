# Local test bundle

This directory contains a sample Phase 6 local-testing runbook and helper
notes. They run on a **host machine with adb** and a connected test device
(arm64, Android 13+ recommended).

## Files

- `phase6_runbook.md` — step-by-step verification of the Phase 6 acceptance
  criteria (2 container apps + shortcuts + 1 real Xposed module hook).
- `pinned_sample_module.md` — how to obtain/build a known-good sample Xposed
  module for the container (legacy API) and pin its version for the test.

Nothing here is shipped inside the app; it is test documentation only.
