# Vector pinned source

Pinned upstream: https://github.com/JingMatrix/Vector

- Commit: `ddeed8c` (main, 2026-10-01, "Update dependencies, mirror libxposed
  after upstream was suspended (#989)")
- Lineage: Vector v2.2; GPL-3.0.
- Submodules (LSPlant, Dobby, libxposed module/service APIs) are pinned by
  Vector's own .gitmodules at this commit; `build_vector.sh` clones with
  `--recurse-submodules` and records the resulting submodule hashes in the
  artifact manifest.

Rationale: newest upstream main; supports Android 8.1–17, legacy Xposed API +
modern libxposed API; GPL-3.0 compatible with this project.

What we build from it (and why we do NOT ship it as a Zygisk module):

1. `libvector_inject.so` — the native injection payload (lspd native sources +
   LSPlant + Dobby linked in) loaded into the container's zygote via the
   app_process wrapper (LD_PRELOAD). No Magisk/KernelSU/Zygisk exists in the
   container; this is the Riru-style direct load adapted to a rootfs we own.
2. `xposed.dex` / services artifacts — the XposedBridge classpath + service
   side, placed into the rootfs next to the injector.
3. Manager UI: the Vector manager APK (Compose manager, no WebUI since
   Vector 2.0) is installed as a normal container app so module list, scope,
   enable/disable, and logs are available inside the VM.

Version matching rule: artifacts must be built against the same Android/ART
version as the rootfs (see AGENT.md §3). A mismatch is a fail state.
