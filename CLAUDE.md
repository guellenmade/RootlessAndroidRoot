# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**RootlessVM** (repo: `RootlessAndroidRoot`) — a FOSS Android app (GPL-3.0-or-later) that runs a fully virtualized Android environment on an **unrooted** host device. Inside the container the user has root (`proot -0` fake-root) and a working Xposed framework (built from [Vector](https://github.com/JingMatrix/Vector) source, pinned commit `ddeed8c`).

- `applicationId`: `io.github.guellenmade.rootlessvm`
- minSdk 28, targetSdk 36, compileSdk 36
- **Read `AGENT.md` first** — it is the living agent guide with all ADRs, conventions, pinned sources, and phase history. `DOCUMENTATION.md` has full human-facing docs.

## Build & test commands

```bash
# Host app (requires JDK 17 + Android SDK; CI pins Gradle 8.14 via setup-gradle)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest          # all unit tests
./gradlew :app:testDebugUnitTest --tests "io.github.guellenmade.rootlessvm.FailStateTest"  # single test class

# Vector (Xposed) artifacts — clones upstream at pinned commit, needs Android NDK
vector/build_vector.sh <workdir>

# proot binary per ABI (arm64-v8a | armeabi-v7a | x86_64) — needs Android NDK
proot/build_proot.sh <workdir> <abi>
```

**No `gradlew` wrapper is checked in.** CI uses `gradle/actions/setup-gradle@v4` with `gradle-version: "8.14"` (required by AGP 8.13.0). To build locally, either install Gradle 8.14 or run `gradle wrapper` once and commit the generated wrapper.

**Toolchain versions** (see `gradle/libs.versions.toml`): AGP 8.13.0, Kotlin 2.2.10, Compose BOM 2026.01.00, Compose Compiler 2.2.10-1.0.0, kotlinx-serialization 1.9.0.

## Architecture

```
Host Android (unrooted)
└── RootlessVM app (Kotlin/Compose, single Gradle module :app)
    ├── VmService (foreground service)
    │    └── proot (native binary, arm64/arm/x86_64)
    │         └── Android rootfs (app-private storage)
    │              ├── zygote (patched: app_process wrapper + LD_PRELOAD libvector_inject.so)
    │              │    └── Vector (LSPlant + Dobby + XposedBridge/dex + lspd)
    │              ├── su wrapper (policy JSON from host)
    │              └── container apps (hooked by Xposed modules, llvmpipe rendering)
    ├── ShortcutSync (pinned launcher shortcuts → boot VM → open app)
    └── DisplaySession (container screencap stream → host Compose canvas + input injection)
```

**Source layout** (`app/src/main/kotlin/io/github/guellenmade/rootlessvm/`):

| Package | Responsibility |
|---|---|
| `vm/` | VmService lifecycle, ProotCommandBuilder, VmController, ContainerSession, DisplaySession, ProotProvisioner, VectorProvisioner, GpuDetector, ContainerPaths, FailState |
| `shortcut/` | ShortcutSync, AdaptiveIconFactory |
| `xposed/` | XposedModuleStore (container module config) |
| `root/` | SuPolicyStore (per-app grant/deny JSON) |
| `data/` | RootfsCatalog, RootfsInstaller (SHA-256 verified), RootfsManifestStore, SettingsStore, ContainerAppStore |
| `ui/` | Compose screens + ViewModels (RootlessApp, MainActivity, MainViewModel, ShortcutLaunchActivity) |
| `di/` | ServiceLocator (hand-written singleton wiring, no DI framework) |

**Runtime scripts** (`app/src/main/assets/runtime/`): `start_container.sh`, `entry.sh`, `exec_in_container.sh`, `install_apk_in_container.sh`, `su`, `app_process_wrapper.sh`, `policy_check.sh`, `list_packages.sh`.

**Native builds** (not Gradle modules):
- `vector/` — `build_vector.sh` (clones Vector @ `ddeed8c`), `deploy_into_rootfs.sh`, `PINNED.md`
- `proot/` — `build_proot.sh` (per-ABI cross-compile with NDK clang)

## Conventions

- **Kotlin**, 4-space indent, no wildcard imports, no trailing whitespace.
- **MVVM**: `ui/` screens are stateless Composables; state via `StateFlow` in ViewModels. No Android framework calls inside ViewModels.
- **No DI framework** — hand-written `ServiceLocator` wires singletons. Keep it that way.
- **Persistence**: kotlinx.serialization JSON files in app-private storage (ADR-003). No Room, no ContentProviders.
- **No `TODO`/`FIXME` markers.** Unimplemented = explicit fail state or listed under Known limitations.
- **Error handling**: every user-visible failure goes through `FailState` (sealed class in `vm/FailState.kt`) with a reason + user message. Clean abort, never half-working states.
- **Shell scripts**: `set -eu`, bash, LF line endings.
- **Comments**: only where code cannot speak.

## Key ADRs (full text in AGENT.md §7)

- **ADR-002**: proot is the baseline; AVF/pKVM is roadmap only.
- **ADR-003**: JSON stores, not Room.
- **ADR-004**: No prebuilt blobs in git — proot and Vector built from pinned source in CI.
- **ADR-005**: Xposed injection via zygote `app_process64` wrapper + `LD_PRELOAD` (no Zygisk/Magisk).
- **ADR-006**: Display = container `screencap` polling stream (v1).
- **ADR-007**: ~~Firewall = VpnService deny-all~~ — **REMOVED**: VpnService is system-wide on Android (blocked ALL device internet, not just the container); per-UID filtering is impossible without root. Firewall deleted.
- **ADR-008**: Rootfs catalog as bundled asset (`assets/rootfs/catalog.json`), images on GitHub Releases.
- **ADR-009**: Proot provisioning chain: bundled asset → GitHub Releases download (SHA-256) → UnsupportedAbi fail state.

## CI

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/android.yml` | push/PR to main, vibe/** | assembleDebug, testDebugUnitTest, assembleRelease |
| `.github/workflows/vector.yml` | push/PR/dispatch | Build Vector artifacts + proot from pinned source |
| `.github/workflows/release.yml` | tag `v*` | Build signed release APK, publish GitHub Release |
| `.github/workflows/proot-release.yml` | (manual/dispatch) | Build + publish per-ABI proot binaries (`proot-<abi>` tags) |
| `.github/workflows/generate-keystore.yml` | (manual) | Generate release signing keystore |

## Important files

- `AGENT.md` — living agent guide: ADRs, conventions, pinned sources, open tasks, phase changelog. **Update it when making architectural changes.**
- `DOCUMENTATION.md` — full human-facing docs (features, architecture, compatibility, FAQ).
- `vector/PINNED.md` — pinned Vector commit rationale and artifact manifest.
- `app/src/main/assets/rootfs/catalog.json` — rootfs + Vector artifact catalog (SHA-256 fields are placeholders until real artifacts are published).
- `docs/local-testing/` — Phase 6 on-device test runbook.
