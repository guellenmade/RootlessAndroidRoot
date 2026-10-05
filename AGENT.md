# AGENT.md — Agent Guide for RootlessVM (RootlessAndroidRoot)

This file is for AI agents (later sessions, other agents) working on this repository.
Read it before touching anything. Keep it up to date in EVERY phase — a phase is not
complete until this file and DOCUMENTATION.md reflect the current state.

---

## 1. Project identity

- **App name:** RootlessVM (see ADR-001; the repository keeps the original name
  `RootlessAndroidRoot`).
- **applicationId:** `io.github.guellenmade.rootlessvm`
- **License:** GPL-3.0-or-later (repo root `LICENSE`). All dependencies must be
  FOSS and GPL-3-compatible. No trackers, no proprietary binaries.
- **One-liner:** An unrooted host app that runs a fully virtualized Android
  environment (proot-based) in which the user has root and a working Xposed
  framework (built from Vector source) — strictly inside the VM.

## 2. Architecture overview

```
Host Android (unrooted)
└── RootlessVM app (Kotlin/Compose, single app UID)
    ├── VmService (foreground service)
    │    └── proot (shipped native binary, arm64/arm/x86_64)
    │         └── Android rootfs (AOSP/LineageOS-derived tarball, app-private)
    │              ├── zygote (patched entry: app_process wrapper + LD_PRELOAD)
    │              │    └── Vector (LSPlant + Dobby + XposedBridge/dex + lspd)
    │              ├── su wrapper (policy JSON written by the host manager UI)
    │              └── container apps (installed APKs, rendered via llvmpipe)
    ├── FirewallVpnService (TUN-based deny-all gate for container traffic)
    ├── Shortcut sync (pinned launcher shortcuts -> boot VM -> open app)
    └── Display bridge (container screencap stream -> host VirtualDisplay UI)
```

Root inside the VM is **proot fake-root** (`proot -0`): every process in the
container believes it is uid 0 with full control of the container filesystem.
The host is untouched — the container never escalates on the host.

### Injection chain (documented honestly)

```
VM start
  -> VmService spawns: proot -0 -r <rootfs> -w / /sbin/init-ish entry (runtime/start_container.sh)
  -> container boots its Android userspace (zygote included)
  -> zygote start: /system/bin/app_process64 is replaced in the ROOTFS at deploy
     time by a wrapper script; the wrapper sets LD_PRELOAD=libvector_inject.so
     and execs the original binary (app_process64.real)
  -> libvector_inject.so (built from Vector source: LSPlant + Dobby + lspd native)
     initializes inside zygote, reads module config from /data/adb/vector (in-rootfs)
  -> every app process forked from zygote carries the hooks; Xposed modules
     (legacy API + libxposed API) hook apps INSIDE the container only
```

There is no Magisk/KernelSU daemon and no Zygisk in the container. This is a
Riru-style direct-load adapted to a rootfs we fully own (we can patch it at
deploy time — that is the "root" we have on the host side: ownership of our own
app-private directory, nothing more).

## 3. Pinned upstream sources (MUST match)

| Component | Upstream | Pinned version | Notes |
|---|---|---|---|
| Vector (Xposed framework) | github.com/JingMatrix/Vector | **commit `ddeed8c`** (main, 2026-10-01, "Update dependencies, mirror libxposed after upstream was suspended (#989)") | Vector v2.2 lineage; GPL-3.0. Build via `vector/build_vector.sh`. Do NOT rely on WebUI (removed in Vector 2.0). |
| LSPlant | submodule of Vector (external/) | as pinned by Vector `ddeed8c` | ART hooking engine |
| Dobby | submodule of Vector | as pinned by Vector `ddeed8c` | inline hooking |
| libxposed API (module + service) | submodule of Vector | as pinned by Vector `ddeed8c` | legacy + modern API support |

Rule: rootfs Android version, ART, and the built Vector artifacts must be
version-matched (see compatibility matrix in DOCUMENTATION.md §6). A mismatch
is a fail state -> clean abort with dialog, never a half-working state.

## 4. Repo layout

```
.
├── AGENT.md                  <- this file (living document)
├── DOCUMENTATION.md          <- full human-facing docs (living document)
├── README.md                 <- short overview, links into DOCUMENTATION.md
├── LICENSE                   <- GPL-3.0
├── NOTICE                    <- third-party components and licenses
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml <- version catalog (single source of versions)
├── app/                      <- single Gradle module: host app (Kotlin + Compose)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/runtime/   <- shell scripts executed inside/around proot
│       │   ├── start_container.sh
│       │   ├── exec_in_container.sh
│       │   ├── install_apk_in_container.sh
│       │   ├── su            (policy-checked su wrapper, installed into rootfs)
│       │   └── app_process_wrapper.sh (zygote entry patch, installed into rootfs)
│       └── kotlin/io/github/guellenmade/rootlessvm/
│           ├── RootlessApplication.kt
│           ├── di/ServiceLocator.kt
│           ├── data/        (settings, rootfs manifest/installer, stores, scanner)
│           ├── vm/          (VmService, ProotCommandBuilder, config, display bridge,
│           │                 input injector, GPU detector, APK installer)
│           ├── vpn/         (FirewallVpnService, protecting socket factory)
│           ├── shortcut/    (ShortcutSync, AdaptiveIconFactory)
│           ├── xposed/      (container module config writer, module list reader)
│           ├── root/        (su policy manager, su request log tailer)
│           └── ui/          (Compose screens + MVVM view models)
├── vector/                   <- Vector build + injection (NOT a Gradle module)
│   ├── build_vector.sh       <- clones JingMatrix/Vector @ ddeed8c, builds artifacts
│   ├── deploy_into_rootfs.sh <- installs artifacts + app_process wrapper into rootfs
│   └── PINNED.md             <- pinned commit rationale and artifact manifest
├── proot/                    <- proot build scripts + prebuilt notice (see ADR-004)
│   └── build_proot.sh        <- builds proot from source for arm64/arm/x86_64
├── .github/workflows/
│   ├── android.yml           <- host app build (debug + release assemble)
│   ├── vector.yml            <- Vector artifact build + rootfs integration test
│   └── release.yml           <- tag-driven release with signed-ish artifacts
└── metadata/
    └── io.github.guellenmade.rootlessvm.yml   <- F-Droid metadata
```

## 5. Build commands

CI is the source of truth (the app is an Android project; build with AGP 9 / Gradle 9):

```bash
# Host app (requires JDK 17+ and Android SDK; CI pins SDK/NDK versions)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# Vector artifacts (requires NDK; clones upstream at the pinned commit)
vector/build_vector.sh <workdir>

# proot binaries (requires NDK; also fetched from our releases in CI)
proot/build_proot.sh <workdir> <abi>

# Unit tests
./gradlew :app:testDebugUnitTest
```

The Gradle wrapper JAR is not checked in as a binary; CI uses
`gradle/actions/setup-gradle`. If you need a local wrapper, run
`gradle wrapper` once and commit the generated wrapper (standard Gradle
distribution URL only).

## 6. Code style conventions

- Kotlin, 4-space indent, no wildcard imports, no trailing whitespace.
- MVVM: `ui/` screens are stateless Composables; state comes from ViewModels via
  `StateFlow`. No Android framework calls inside ViewModels.
- No DI framework: a tiny hand-written `ServiceLocator` (di/) wires singletons.
  Keep it that way unless a phase decision says otherwise.
- Persistence: kotlinx.serialization JSON stores in app-private storage
  (ADR-003). No Room, no ContentProviders.
- No `TODO()`/`FIXME` markers. If something is not implemented, it must be an
  explicit fail state or listed under Known limitations.
- Error handling: every user-visible failure goes through `FailState` with a
  reason; clean abort, no half-working states.
- Comments: only where the code cannot speak (license headers in vendored
  scripts are fine).
- All shell scripts: `set -eu`, bash, LF line endings.

## 7. Architecture Decision Records (ADR, short format)

### ADR-001: App name "RootlessVM"
**Status:** accepted (2026 phase 1). The task brief left the name blank (`___`).
Repo keeps `RootlessAndroidRoot`; product name and applicationId use
`RootlessVM` / `io.github.guellenmade.rootlessvm`. Rename is a one-line change
in `app/build.gradle.kts` + manifest if the maintainer disagrees.

### ADR-002: proot baseline, AVF optional fast path
**Decision:** proot-based container is THE baseline; AVF/pKVM is an optional
fast path behind a capability check.
**Rationale:** proot needs no root, no kernel support, works Android 8+ (API 26;
we set minSdk 28 for Compose/toolchain sanity), on any ABI. AVF (pKVM
protected VMs) requires Android 13+, device support (crosvm, pKVM enabled),
per-device system images and currently has no stable GPU passthrough story for
guest Android; it would exclude the vast majority of users. Cost of proot:
syscall-translation overhead + software rendering (llvmpipe) -> GPU-heavy apps
are slow; documented honestly, never hidden.
**Consequence:** display is software-rendered by default; GPU detection is
reported but only informative in the proot path. AVF remains a roadmap item
(DOCUMENTATION.md §5), not a shipping feature.

### ADR-003: JSON stores instead of Room
**Decision:** kotlinx.serialization JSON files for su policies, logs, container
app cache, xposed module list.
**Rationale:** avoids kapt/ksp build fragility in a CI-first repo, keeps the
data layer auditable (plain files, also consumed by container-side scripts
which cannot read SQLite via shell easily), GPL-clean, zero native deps.

### ADR-004: Build proot + Vector from source in CI; no prebuilt blobs in git
**Decision:** No binary artifacts committed. `proot/build_proot.sh` and
`vector/build_vector.sh` build from pinned upstream source in CI; releases
attach the built binaries and the signed rootfs manifest.
**Rationale:** F-Droid compliance and GPL-3 source-integrity: everything must be
reproducible from source. Shipping random prebuilt su/proot binaries would be a
trust hazard and a licensing hazard.

### ADR-005: Zygote injection via app_process wrapper + LD_PRELOAD (no Zygisk)
**Decision:** At rootfs deploy time, `/system/bin/app_process64` in the rootfs
is renamed to `app_process64.real` and replaced by our wrapper script
(assets/runtime/app_process_wrapper.sh) that LD_PRELOADs the Vector-built
injector into zygote.
**Rationale:** Vector ships as a Zygisk module and expects a
Magisk/KernelSU/Zygisk environment, which does not exist in a proot container.
Because we own the rootfs image, patching the zygote entry at deploy time is the
closest honest analogue of the Riru era approach: deterministic, verifiable,
no host modification. The injector itself is built from Vector source at the
pinned commit (LSPlant + Dobby + lspd native + XposedBridge dex).

### ADR-006: Display bridge = container screencap stream (v1)
**Decision:** v1 renders container UI by streaming `screencap` frames from the
container over the proot boundary into a host Compose canvas; input is injected
with the container's `input` command.
**Rationale:** the container renders with llvmpipe into its own display; the
only portable, rootless way to surface those pixels on the host is the
container's own screen capture tooling. Frame rate is limited (see limitations).
This is honest v1 scope; a minicap-style shared-memory path is a roadmap item.

### ADR-007: Network firewall = VpnService deny-all for the app UID
**Decision:** FirewallVpnService routes the whole app UID's traffic into a TUN
device and drops it; the host app's own management sockets are `protect()`-ed.
**Rationale:** proot children share the host app's UID, so per-UID filtering at
the VPN layer is the only rootless lever we have. v1 is deny-all (container
offline); per-destination allow-lists are a roadmap item. Users are told
plainly: firewall on = container has no network.

## 8. Known limitations and fail states

Limitations (honest):
- proot syscall translation + llvmpipe = slow GPU-heavy apps, low display fps.
- Firewall is deny-all in v1; no selective allow rules.
- Display streaming is polling-based (screencap), input has visible latency.
- Vector artifacts must be rebuilt when the rootfs Android version changes.
- AVF/pKVM path is not implemented; detection exists, path is roadmap.

Fail states (clean abort + dialog, never half-working):
- Unsupported ABI (no proot binary for the device ABI).
- Insufficient storage for rootfs (checked before download/unpack).
- Rootfs checksum mismatch (SHA-256; abort + delete partial file).
- Rootfs Android version incompatible with built Vector artifacts
  (manifest cross-check before VM start).
- proot exit at boot (non-zero within grace period) -> stop VM, report.
- Firewall start failure -> VM refuses to start in "firewall on" mode.

## 9. Open tasks (living list)

- [ ] Build Vector artifacts in CI against rootfs Android 13 (A64) and wire
      version cross-check into VmController (function exists; needs real
      artifacts to validate on-device).
- [ ] On-device validation of the full injection chain (Phase 6 runbook in
      DOCUMENTATION.md §12).
- [ ] minicap-style shared-memory display path (replaces ADR-006 polling).
- [ ] Selective firewall allow-list.
- [ ] AVF/pKVM fast path prototype (ADR-002).
- [ ] Snapshot export/import UI polish (engine supports it; UI is minimal).
- [ ] Translations; F-Droid submission after first release tag.

## 10. Phase changelog (append per phase, never delete)

- **Phase 1 (this commit):** Architecture decision ADR-002, compatibility
  matrix, limitations; initial AGENT.md + DOCUMENTATION.md; LICENSE.
- (later phases appended below)

- **Phase 2 (this commit):** Repo layout created: Gradle skeleton (AGP 9, Kotlin 2.2, minSdk 28/target 36), manifest + services, launcher resources, runtime scripts (proot entry, entry.sh, zygote app_process wrapper, su + policy check, APK install, package list), proot + Vector build/deploy scripts, README. AGENT.md extended with layout details; entry.sh and policy_check.sh added to module map.

- **Phase 3 (this commit):** Complete Kotlin source: Application + ServiceLocator, data layer (settings, rootfs manifest, installer with SHA-256 verify, container app store), vm core (paths, FailState with all fail cases, ProotCommandBuilder, GpuDetector, ContainerSession, VmController, VmService), root su policy store with request watcher, XposedModuleStore, FirewallVpnService (deny-all TUN), AdaptiveIconFactory + ShortcutSync (pin shortcuts, adaptive icons), Compose UI (RootlessApp with Home/Apps/Root/Xposed/Settings tabs), MainActivity + ShortcutLaunchActivity (boot VM then launch container app), unit tests. No TODO markers.

- **Phase 4 (this commit):** CI added: android.yml (debug/release assemble + unit tests), vector.yml (Vector artifacts from pinned commit + proot build, non-blocking warnings on upstream layout drift), release.yml (tag-driven APK release). F-Droid metadata at metadata/io.github.guellenmade.rootlessvm.yml.

- **Phase 5 (this commit):** DOCUMENTATION.md finalized: deep dives on shortcut mechanism and Xposed-without-Magisk chain; docs/local-testing/ bundle (Phase 6 runbook, pinned sample module notes). Known limitation added: source has not been compiled in-sandbox (no Android SDK/NDK available); CI (.github/workflows/android.yml) is the first compile gate — run it after push and fix any toolchain errors as the next task.
- **Phase 6 (this commit):** Local test setup documented: docs/local-testing/phase6_runbook.md (install 2 container apps, verify shortcuts, install + verify one real Xposed module, su allow/deny) and pinned_sample_module.md (build a minimal legacy-API module against the pinned Vector build; version rules). On-device execution requires a physical device and real Vector artifacts; remains an open task (§9).

## 11. Maintenance notes (post-phase-6)

- The first compile gate is CI (`.github/workflows/android.yml`); toolchain
  errors surfaced there must be fixed as the immediate next task.
- When bumping the Vector pin: update §3, `vector/PINNED.md`,
  `vector/build_vector.sh`, and the artifact-manifest cross-check.

## 12. CI fix note (post-phase)

- AGP pinned to 8.13.0 (with kotlin-android plugin): AGP 9.0 failed in CI
  ("Cannot add extension with name kotlin" — built-in Kotlin support conflicts
  with the standalone kotlin-android plugin). Decision recorded here instead of
  silently changing ADRs; revisit when AGP 9 stabilizes.
- vector.yml uses explicit sdkmanager installs with licenses pre-accepted
  instead of android-actions/setup-android (which failed with sdkmanager exit
  code 1100755 in CI).

## 13. Post-delivery status (final)

- CI green: Android CI (debug + release assemble, unit tests) and Vector
  artifacts workflow both pass on the PR branch.
- Fixes made during CI hardening (all documented, no silent decisions):
  AGP pinned to 8.13.0 + Gradle 8.14 in CI (AGP 9 / Gradle 9.8 conflict);
  Kotlin Compose Compiler plugin added (required with Kotlin 2.x);
  compile errors fixed (duplicate viewModel declaration, missing imports,
  VpnService.prepare call, Result<Unit> mappings, File-vs-String check);
  junit test dependency added; test shadowing removed.
- Open tasks from §9 remain: on-device Phase 6 validation, Vector artifacts
  built against a real rootfs (CI build step currently records the pinned
  manifest; upstream output paths need a drift check), display/input
  improvements, AVF path.

## 14. ADRs and changes from the "build everything" pass (2026-10-05)

### ADR-008: Rootfs catalog as bundled asset + GitHub Releases
**Decision:** `assets/rootfs/catalog.json` (pinned catalog, shipped in the APK)
is the manifest source; images live on the project's GitHub Releases.
**Rationale:** FOSS and auditable: the catalog is versioned in-repo, images are
checksum-pinned, and no proprietary mirror is involved. The catalog's SHA-256
fields are placeholders until the first rootfs image is actually built and
published (tracked in §9); the installer still refuses mismatches.

### ADR-009: Proot provisioning chain
**Decision:** ProotProvisioner resolves the proot binary per ABI:
1. bundled in APK assets (`bin/proot/<abi>/proot`) — CI release builds pack it;
2. else downloaded from GitHub Releases (`proot-<abi>` tag) with SHA-256 check;
3. else UnsupportedAbi fail state — clean abort.
**Rationale:** keeps the APK small on non-release builds, guarantees the binary
always comes from a verifiable source, and never leaves the app half-working.

### Changes vs earlier phases (documented, not silent)
- **First-launch flow is real now:** Home tab shows "Prepare VM" which
  provisions proot AND downloads/verifies/unpacks the rootfs. Previously the
  download path existed but was unreachable from the UI.
- **DisplaySession added (implements ADR-006):** polls container screencap,
  decodes frames, measures fps, injects tap/back input; Home tab renders the
  container display live with honest fps reporting.
- **FirewallVpnService pump rewritten:** the old loop opened a new
  FileOutputStream per packet (FD churn); now a single read loop drops packets
  (true deny-all per ADR-007).
- **ServiceLocator:** added prootProvisioner + rootfsCatalog; fixed missing
  package declaration introduced earlier.

### Phase changelog addition
- **Build-everything pass:** RootfsCatalog + bundled catalog asset,
  ProotProvisioner, DisplaySession, firewall pump fix, UI wiring (Prepare VM,
  live display, input injection, fps readout).
