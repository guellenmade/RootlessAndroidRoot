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
**Status: REMOVED (decision reversed, 2026-10-07).**
**Original decision:** FirewallVpnService routes the whole app UID's traffic
into a TUN device and drops it; the host app's own management sockets are
`protect()`-ed.
**Why removed:** Android's VpnService is system-wide — it intercepts traffic
from ALL apps on the device, not just our UID. The `addRoute("0.0.0.0", 0)`
implementation therefore blocked internet for the entire device while the VM
ran (on-device confirmed: not even `ping 1.1.1.1` worked). Per-UID filtering
is impossible via VpnService without root, `protect()` only covers our own
sockets, and no rootless per-app firewall API exists on Android. The feature
as designed could never work as advertised. The firewall (service, setting,
UI toggle, manifest entry, fail state) is deleted; the container has network
access. The container remains sandboxed to the app-private directory, which
bounds the blast radius; users who need isolation can revoke the INTERNET
permission (Android 13+ per-app network revocation) or use a work profile.

## 8. Known limitations and fail states

Limitations (honest):
- proot syscall translation + llvmpipe = slow GPU-heavy apps, low display fps.
- Container has network access; the v1 firewall was removed (ADR-007) because
  VpnService is system-wide on Android and blocked all device internet.
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

## 9. Open tasks (living list)

- [ ] Build Vector artifacts in CI against rootfs Android 13 (A64) and wire
      version cross-check into VmController (function exists; needs real
      artifacts to validate on-device).
- [ ] On-device validation of the full injection chain (Phase 6 runbook in
      DOCUMENTATION.md §12).
- [ ] minicap-style shared-memory display path (replaces ADR-006 polling).
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

### 15. Status after build-everything pass
- CI green (Android CI + Vector artifacts) on the PR branch.
- The app is now feature-complete per the goal: first-launch rootfs download
  with checksum verification, proot runtime, su + policy manager, Vector
  injection chain (pinned ddeed8c), shortcuts with adaptive icons, live
  display with input, VM controls, firewall, fail states with clean aborts.
- Still requiring real-world artifacts (cannot be produced in this sandbox,
  tracked in §9): an actual AOSP/LineageOS-derived rootfs image + real SHA-256
  in the catalog; proot binaries attached to a `proot-<abi>` release; Vector
  artifacts built against that exact rootfs; on-device Phase 6 validation.

### 16. "Download everything" button (2026-10-05)
**Decision:** one first-launch button downloads and installs the full stack:
proot (ADR-009 chain) -> rootfs (SHA-256 verified, ADR-008 catalog) ->
Vector artifacts (new VectorProvisioner: downloads libvector_inject.so,
xposed.dex, vector-manager.apk from the catalog, verifies each SHA-256,
deploys into the rootfs incl. the zygote app_process patch, su tools, and
vector-manifest.json).
**Rationale:** the goal asks for a working first-launch download of the whole
environment; three separate manual steps violate "no half-working states" —
the button either ends fully provisioned or fails cleanly with a dialog.
Catalog now carries a `vector` artifact list (component/commit/apiLevel/url/
sha256); SHA-256 fields remain placeholders until real artifacts are
published (§9).

### 17. First release
- `v0.1.0-alpha` tagged and published via release.yml; APK
  (app-release-unsigned.apk) attached: https://github.com/guellenmade/RootlessAndroidRoot/releases/tag/v0.1.0-alpha
- Release notes state honestly that catalog artifacts are placeholders and
  the download flow aborts cleanly at checksum verification until real
  rootfs/proot/Vector artifacts are published (§9).
- APK is unsigned; signing setup (F-Droid or maintainer key) is an open task.

### 18. Signing fix (v0.1.0-alpha2)
- Bug: v0.1.0-alpha shipped an unsigned APK -> Android install failed with
  INSTALL_PARSE_FAILED_NO_CERTIFICATE.
- Fix: release buildType now falls back to the debug signing config when no
  release keystore is configured, so published APKs are ALWAYS installable.
  When SIGNING_KEYSTORE_BASE64 + SIGNING_STORE_PASSWORD secrets are set, the
  pipeline signs with the real keystore instead (generate-keystore.yml creates
  it; keystore is stored as a repo secret, never committed).
- v0.1.0-alpha2 published with a signed APK; the broken unsigned asset on
  v0.1.0-alpha was deleted and its notes point to alpha2.

### 19. ABI false-positive fix (v0.1.0-alpha3)
- Bug report: arm64-v8a device got "architecture not supported" during
  Download-everything. Real cause: the proot binary download failed
  (placeholder release URL, artifacts not published) and the ViewModel
  mapped ANY provisioning failure to UnsupportedAbi.
- Fixes:
  1. BuildAbi.current() now uses Build.SUPPORTED_ABIS (Android truth), not
         JVM os.arch; explicit isSupported() check runs before the catalog
     lookup, so UnsupportedAbi can only mean a genuinely unsupported ABI.
  2. New fail state RuntimeArtifactUnavailable with the real cause; proot
     provisioning failures map to it, not to UnsupportedAbi.
  3. ProotProvisioner reuses an already-provisioned binary (idempotent) and
     keeps its failure reason intact end-to-end.

### 20. proot artifact pipeline (2026-10-05)
- Fixed proot/build_proot.sh: it ran `cd <workdir>` before creating it
  (CI log: "cd: proot-work: No such file or directory"), so no binary was
  ever built. Now: mkdir first, NDK clang CC per ABI (aarch64-linux-android24-clang
  etc.), pinned proot commit.
- New proot-release.yml: builds proot for arm64-v8a/armeabi-v7a/x86_64 with
  NDK 27.2 and publishes per-ABI releases tagged `proot-<abi>` with assets
  `proot` + `proot.sha256` — exactly the URL scheme ProotProvisioner
  downloads (ADR-009 step 2 becomes real once this runs).
- App-side unchanged: ProotProvisioner downloads from
  releases/download/proot-<abi>/proot and verifies proot.sha256.

- proot build script corrected: upstream has no root Makefile ("make: No targets
  specified"); the real build is `make -C src` with cross CC from NDK clang
  (proot v5.5.0, 25dc6a3) + libtalloc cross-built from source (2.4.2, LGPL,
  GPL-compatible) since proot links it via pkg-config and Android has no
  talloc package. WITHOUT_PYTHON=1 (no python embedding on Android).

### 21. proot artifacts published (2026-10-05)

Six successive CI failures were diagnosed and fixed in proot/build_proot.sh
and proot-release.yml; the pipeline is now fully green and the artifacts are
live. Root causes and fixes (each verified against the CI log of the failed
run before fixing):

1. Undefined talloc_* symbols at link: command-line LDFLAGS overrides the
   makefile's `LDFLAGS += $(pkg-config --libs talloc)` (command-line vars
   beat += in makefiles), so -ltalloc never reached the link. Fix: pass
   -ltalloc explicitly in our LDFLAGS.
2. `unable to find library -ltalloc`: talloc's waf build only installs a
   shared lib. Fix: additionally build a static archive with the NDK clang.
3. `replace.h not found`: talloc.c includes lib/replace/replace.h; it needs
   waf-generated config.h from bin/default and -D__STDC_WANT_LIB_EXT1__=1;
   the bundled replace.c/closefrom.c are compiled into the archive so all
   symbols resolve statically.
4. `strip: Unable to recognise the format of the input file` (ARM only):
   proot's GNUmakefile uses $(CROSS_COMPILE)strip/objcopy/objdump, which
   resolve to host binutils; host strip accepted x86_64 ELF but not
   aarch32/arm ELF. Fix: pass STRIP=llvm-strip OBJCOPY=llvm-objcopy
   OBJDUMP=llvm-objdump as make variables.
5. Publish job `no matches found for dist/<abi>/proot`: download-artifact
   with merge-multiple flattened everything into dist/. Fix:
   merge-multiple: false and assets at dist/proot-<abi>/.

Published releases (URLs match ProotProvisioner's
`releases/download/proot-%s/proot` + `.sha256` exactly, ADR-009 step 2):
- proot-arm64-v8a, proot-armeabi-v7a, proot-x86_64, each with assets
  `proot` and `proot.sha256`, statically linked, built from pinned proot
  commit 25dc6a3134891f98a79f57ce1c2c1b23ff15cad1 (v5.5.0) + talloc 2.4.2.

Result: the in-app "Download everything" proot step now resolves on real
devices. Remaining placeholder artifacts: rootfs images and Vector
components (catalog sha256s still zero) - the next blocker.

### 22. Why downloads failed on-device: repo is PRIVATE (2026-10-05)

The proot artifacts WERE published (section 21), but the on-device download
still failed with 404: the repository is private, so
`releases/download/...` URLs require authentication. An unauthenticated
HttpURLConnection (the app) gets 404. The "download failed" message gave no
HTTP detail because URL.openStream() throws a bare FileNotFoundException.

Fixes:
1. Release APKs now BUNDLE the proot binaries: release.yml downloads the
   published per-ABI binaries into `app/src/main/assets/bin/proot/<abi>/`
   before assembleRelease. ProotProvisioner's asset-first path (ADR-009
   step 1) now serves every device without any network access. Verified:
   the v0.1.0-alpha4 APK contains assets/bin/proot/{arm64-v8a,armeabi-v7a,
   x86_64}/proot (606320/405808/637464 bytes).
2. ProotProvisioner now uses HttpURLConnection with explicit timeouts,
   redirect following, and reports "HTTP <code>" in the failure detail
   instead of a bare "download failed".

Open decision for the maintainer: making the repository public would also
fix the download path (and is required for the FOSS/F-Droid goal anyway);
the bundle-first path works regardless.

Known limitation (honest): if the repo stays private, the rootfs image and
Vector artifact downloads (catalog URLs) will ALSO fail on-device for the
same reason; bundling or publishing those artifacts publicly remains open
(catalog sha256s are placeholders).

### 23. Repository made public (2026-10-05)

The maintainer switched the repository to public. Verified anonymously
(no auth): both `releases/download/proot-arm64-v8a/proot` (606320 bytes)
and `proot.sha256` return HTTP 200 via the standard redirect chain.
ADR-009 step 2 (release download + sha256 verify) now works on-device;
step 1 (bundled assets, v0.1.0-alpha4) works without network. The
private-repo caveat in section 22 is resolved.

### 24. Real artifacts: rootfs + Vector published (2026-10-05)

The download-everything chain now has real, published, checksum-verified
artifacts for every step. Root causes found on the way (each verified in
the failed run's log):

Rootfs (ADR-010):
- Rejected Google GSI zips: their license forbids redistribution.
- Rejected AOSP emulator system images (Apache-2.0): the API-33
  system.img is a GPT disk image with a dynamic-partition `super`
  (liblp) — userspace extraction would need lpunpack; not viable here.
- Chosen: official Waydroid OTA LineageOS 20.0 VANILLA images
  (Android 13 / API 33, GPL+Apache, no GMS, redistribution OK).
  system.img and vendor.img are standalone ext4/erofs images; extracted
  userspace-only (python unsparse + debugfs rdump / fsck.erofs).
  Pinned builds: lineage-20.0-20260927 (system+vendor, arm64 and x86_64).
  System image is system-as-root: extracted system image IS the
  container root; vendor merged under /vendor.
- Fixes made: sdkmanager path is system-images/android-33/ (hyphenated);
  vendor OTA URL has no "mainline/" segment; dead debugfs before erofs
  branch etc. Release `rootfs-aosp-13` holds
  rootfs-aosp-13-{arm64-v8a,x86_64}.tar.xz (~700 MB each) + .sha256.

Vector (ADR-011):
- Upstream at pinned commit ddeed8c is Zygisk-first: there is no
  standalone "liblspd.so"/"xposed.dex" pair; upstream packages via
  `./gradlew zipAll` into Vector-v*-Release.zip containing
  lib/<abi>/libzygisk.so, framework/vector.dex, manager.apk,
  daemon.apk, bin/dex2oat, bin/liboat_hook.so.
- build_vector.sh now builds via upstream's own zipAll and maps:
  libvector_inject.so <- lib/arm64-v8a/libzygisk.so;
  xposed.dex <- framework/vector.dex; vector-manager.apk <- manager.apk
  (plus vector-daemon.apk kept in the release).
- Build fixes: JDK 21 (source release 21), SDK-bundled ninja 1.10
  replaced with pip ninja 1.13 (CMake C++20 module scan requires 1.11+),
  upstream default branch is master (fetch pinned commit directly),
  workdir mkdir before cd. Release `vector-aosp-13` holds all artifacts.
- Honest note: zygisk-entry artifacts repurposed for app_process
  injection (ADR-005) is UNVALIDATED on-device. The libzygisk.so entry
  point expects a Zygisk loader; whether it initializes under our
  LD_PRELOAD app_process wrapper in the container is the next
  on-device test (Phase 6 runbook). The manager.apk/daemon.apk are
  plain APKs and install normally in the container.

catalog.json now carries the real sha256s and sizes for all entries
(rootfs from the release .sha256 assets, vector computed locally from the
downloaded release assets). No zero-checksum placeholders remain.
Remaining open: on-device end-to-end validation (Phase 6), AVF fast path,
rootfs Android version pin vs Vector API target drift automation.

### 25. Container start fix: /vm bind, entry.sh deploy, boot-log capture (2026-10-05)

On-device "download everything" ended with `proot exited with code -4`
(SIGILL). Two concrete launch-path bugs were found and fixed (commit 890b508):

1. **`/vm` was never bind-mounted.** ProotCommandBuilder asked proot to run
   `/system/bin/sh /vm/entry.sh`, but no `-b ...:/vm` bind existed and
   entry.sh was never deployed (only su/policy_check.sh/app_process_wrapper.sh
   were). Fix: both build() and execCommand() now pass
   `-b <runtimeDir>:/vm`, and VectorProvisioner.installSuTools additionally
   copies the `runtime/entry.sh` asset to `<runtimeDir>/vm/entry.sh`
   (executable) via the new installVmScripts().
2. **proot stderr was discarded**, making SIGILL undiagnosable.
   VmController.startVmBlocking now redirects combined proot output to
   `<base>/proot-boot.log` (Redirect.appendTo) and, on failure, appends the
   last 15 lines to FailState.ProotBootFailure's user dialog.

Honest status: the SIGILL *root cause* is still unconfirmed. Candidates:
primary-ABI mismatch (device where BuildAbi picks an ABI whose proot binary
cannot execute) or an unsupported instruction in the static binary. The new
boot-log capture will show proot's own diagnostic on the next device run.
Deploy note: entry.sh is placed during provisioning; existing installs must
re-run the deploy step (or clear app data) before the /vm bind has content.

### 26. Real cause of the fake "proot exited with code -4" (2026-10-05)

The on-device "-4" was NOT a proot signal death. It was fabricated by
MainViewModel.failFromReason's catch-all `else` branch: a provisioning step
(proot/rootfs/vector) threw an exception whose message matched no known
reason prefix, and the ViewModel replaced it with a hardcoded
`FailState.ProotBootFailure(-4)`, discarding the real error text. That is
also why no proot-boot.log existed: proot never ran.

Fixes (honest error propagation, no fabricated states):
- New `FailState.UnexpectedError(detail)` surfaces the actual exception
  message and asks the user to report it.
- failFromReason now also maps `firewall-start-failure:` and parses the real
  exit code out of `proot-boot-failure:<code>` instead of hardcoding -4.
- Unknown reasons can no longer masquerade as a proot boot failure.

Consequence: the original SIGILL hypothesis for the first report is unproven;
the next device run will show the true failing step and message.

### 27. Wrong rootfs asset URL in catalog (2026-10-05)

The first real on-device error surfaced by the honest error propagation
(alpha7) was a 404-class download failure for
`rootfs-aosp-13-arm64.tar.xz`. Cause: the release asset is named
`rootfs-aosp-13-arm64-v8a.tar.xz` (WD_ARCH name + ABI, per the rootfs build
pipeline), but catalog.json carried `rootfs-aosp-13-arm64.tar.xz`. The x86_64
entry was already correct.

Fix: catalog.json arm64 URL now points at `rootfs-aosp-13-arm64-v8a.tar.xz`.
This closes the chain of masked errors: fabricated -4 -> UnexpectedError ->
real 404 -> fixed catalog entry.

### 28. Artifact drift: releases were rebuilt on every push (2026-10-05)

After fixing the arm64 URL (ADR/sec 27), a HEAD check showed the published
rootfs assets no longer matched the catalog checksums: the rootfs and vector
workflows re-published `rootfs-aosp-13` / `vector-aosp-13` on EVERY push to
main (`gh release upload --clobber`), and the tarballs are not reproducible,
so checksums drifted (arm64 sha256 is now 6407d26d..., vector-manager.apk
196f1766...). The app would have failed checksum verification right after a
successful download.

Fixes:
- catalog.json updated to the CURRENT published sha256/size for all four
  rootfs entries' images and vector-manager.apk (recomputed locally from the
  downloaded release assets, not guessed).
- rootfs-release.yml publish job now (a) only runs on push to main and
  (b) skips entirely if the release already exists — pinned assets are never
  clobbered again. vector.yml publish got the same existence guard.
- Rule going forward: published artifact releases are immutable; to ship new
  artifacts, publish a NEW release tag and update the catalog in the same
  commit.

### 29. Unpack failure 127: Android toybox tar cannot decompress xz (2026-10-05)

The next real on-device error (alpha9) was `unpack-failure:127` after the
rootfs download and checksum both passed. Cause: RootfsInstaller.unpack()
shelled out to `tar -xJf`, but Android's toybox tar has no xz decompressor
(exit 127, command not found). The rootfs tarball is tar.xz, so unpacking
could never work on-device.

Fix (ADR-012): replace the tar subprocess with a pure-Java streaming
extractor in RootfsInstaller using Apache Commons Compress (TarArchiveStream)
+ xz-java (XZFileInputStream), both FOSS and F-Droid-safe. Handles
directories, regular files (executable bit from tar mode), hard links
(copy of the root-relative link target) and symlinks (relative linkName).
R8 keep rules added for both libraries since release builds are minified.
This also makes unpacking deterministic regardless of host toybox variant.

### 30. One more checksum drift: in-flight run clobbered assets pre-guard (2026-10-05)

The alpha10 on-device run failed checksum verification for the rootfs. Cause:
a rootfs-release workflow run that STARTED at 14:37 (before the immutability
guard of sec 28 was pushed) finished its ~18 min build at 14:55 and published
with the OLD clobber logic, replacing the assets AFTER alpha9's catalog was
pinned. Runs started after the guard correctly skip publishing (verified in
the 15:03 run log).

Current pinned values (catalog v3): arm64 sha256
fad4a2dda1ffc7bfe6ec027d4297701b353404c948ca8bdb85d3ac7178ab54d1
(699233300 bytes), x86_64 sha256
b13721c01caa967c8b14dc713624e46e3cb36a127f400b445b50b33fd2f88978
(694699424 bytes). Vector assets verified unchanged (14:50, same sizes and
checksums as catalog). No further clobbering is possible: post-guard runs
skip when the release exists.

### 31. Vector artifact checksum drift + misleading dialog (2026-10-05)

The post-unpack "checksum verification" failure was NOT the rootfs: the
rootfs is verified before unpack, and unpack succeeded. The failing step was
VectorProvisioner.downloadVerified for vector-manager.apk, which throws the
same checksum-mismatch reason — and the dialog printed "Rootfs image failed
checksum verification" unconditionally, sending diagnosis down the wrong
path.

Root cause of the mismatch itself: vector.yml's 14:41 run (started BEFORE
the sec-28 guard landed) rebuilt and clobbered the vector assets at 14:50,
AFTER the catalog pinned checksums computed at 14:40. Same race as sec 30.
Current final values (verified against live assets): vector-manager.apk
b27b81a8..., libvector_inject.so 7def7b8b... and xposed.dex fc5ae6b4...
(unchanged). Post-guard runs (15:03, 16:53) skipped publishing, so no
further drift is possible.

Fixes:
- catalog: vector-manager.apk sha256 repinned to b27b81a8...
- VectorProvisioner appends the component name to the checksum-mismatch
  reason; MainViewModel parses expected/actual back out.
- FailState.ChecksumMismatch now names the expected vs actual hashes and no
  longer claims it is always the "Rootfs image".

### 32. ENOENT copying vector-manager.apk: staging dir never created (2026-10-05)

Alpha12 got the furthest yet: full provisioning chain ran until the Vector
deploy, which crashed with ENOENT on
`<containerData>/staging/vector-manager.apk`. Cause: the deploy code opened
a FileOutputStream on `staging/vector-manager.apk` without ever creating the
`staging` directory. Same latent risk existed for `system/lib64` and
`system/framework` (they happen to exist in the rootfs, but nothing
guaranteed it).

Fix: VectorProvisioner.downloadAndDeploy now mkdirs() before each copy
target: system/lib64, system/framework, and <containerData>/staging.
The entire provisioning chain is now exercised up to Done on-device.
Next frontier: container boot (proot + entry.sh + zygote) — first real
start attempt happens after provisioning completes.

### 33. ADR-013: exec proot from nativeLibraryDir; clipboard; v0.1.1 (2026-10-05)

The alpha13 on-device run finished provisioning completely, then the first
real VM start failed with `proot-boot-failure:-2` (an exception from
ProcessBuilder.start(), not a proot exit). Root cause: on targetSdk >= 29
Android forbids exec() of binaries in app data directories (W^X); our
provisioned proot under <appdata>/app_vm/runtime/bin could never be exec'd.

**ADR-013: proot ships as a native library.** Release builds pack the proot
binary into `jniLibs/<abi>/libproot.so` (release.yml updated accordingly),
the manifest sets `android:extractNativeLibs="true"` so the lib is extracted
to the APK's nativeLibraryDir — the one packaged location where exec() is
allowed (same approach as Termux's targetSdk-28 exception, but compliant:
we keep targetSdk 36). ContainerPaths.prootBin now resolves
`<nativeLibraryDir>/libproot.so` first (registered in RootlessApplication
via useNativeLibDir), falling back to the data-dir copy for non-release
builds. The old assets/bin/proot bundling is retired.

Also in v0.1.1:
- Error dialogs auto-copy the message to the clipboard (LaunchedEffect +
  LocalClipboardManager) and note that in the dialog text.
- versionCode 2, versionName 0.1.1.

### 34. Provisioner ignored bundled libproot.so (2026-10-06)

On v0.1.1, "Download everything" failed with RuntimeArtifactUnavailable for
proot: the ADR-013 change moved the bundled proot from assets to
jniLibs/libproot.so, but ProotProvisioner.provision() still only looked in
assets and then fell back to the release download. On the user's device the
download path failed (network), aborting provisioning even though a working
proot was already bundled in the APK.

Fix: provision() resolves paths.bundledProot (nativeLibraryDir/libproot.so)
FIRST and returns success immediately; the asset and download paths remain
as fallbacks for non-release builds. Verified both fallback URLs still
answer 200 anonymously.

### 35. Boot failure 134 (SIGABRT): bad proot args + diagnostics (2026-10-06)

First on-device proot exec succeeded (ADR-013 works) but the container boot
died with exit 134 = SIGABRT. Inspection of the pinned proot source
(25dc6a3) showed our ProotCommandBuilder used "-L <resolv.conf>", which is
NOT a proot option (not in src/cli/proot.h's option table). proot's option
parser aborts on unknown options. Also verified --rootfs=path IS valid
(same option as -r).

Fixes:
- ProotCommandBuilder: -L removed from both build() and execCommand(); kept
  -r <rootfs> (equivalent to --rootfs=, shorter).
- VmController.startVmBlocking now writes the full command line into
  proot-boot.log and runs a `proot --version` preflight probe, logging its
  rc and output, so a broken binary is immediately distinguishable from
  argument errors. Dialog log tail raised from 15 to 40 lines.

### 36. Security hardening + bug pass (2026-10-07)

Fixes applied on top of v0.1.3 (commit 85f4e80):
- HttpFetch (net/): mandatory HTTPS, connect/read timeouts, redirect
  re-validation for ALL artifact downloads (proot, rootfs, Vector). Replaces
  URL.openStream() (no timeouts) and HttpURLConnection with
  instanceFollowRedirects=true (no redirect scheme re-check).
- RootfsInstaller.unpack: tar-slip/zip-slip protection — tar entries,
  hardlink targets, and symlinks that escape the destination via ../ or
  absolute paths are rejected.
- policy_check.sh: robust policy parsing — matches "uid":N, with the exact
  field structure (prevents uid 1000 matching uid 10001), handles
  pretty-printed JSON.
- su wrapper: exec path fixed for proot -0 (was referencing a non-existent
  su-exec-real binary); granted = exec sh -c "$*" or shell.
- injectInput: passes separate args to `input` (was passing the whole event
  string as one arg, which `input` rejects).
- MainViewModel.rescan: package-list parsing fixed — `pm list packages -3`
  outputs "package:name" (the old regex expected name=version and never
  matched); dumpsys label parsing broadened.
- installApk failure: maps to UnexpectedError with the real message instead
  of a fabricated ProotBootFailure(-3).
- DisplaySession: previous frame's Bitmap recycled (OOM from 250ms polling).
- VmService.onDestroy: stopVm off the main thread (was ANR on waitFor()).
- ShortcutLaunchActivity: lifecycleScope instead of a raw CoroutineScope.
- start_container.sh: invalid -L option removed, bind paths aligned with
  ContainerPaths (legacy script, kept in sync).
- install_apk_in_container.sh: broken session-ID grep fixed.

### 37. ADR-007 firewall removed (2026-10-07)

On-device finding: with the firewall active, the device lost ALL internet
(not even ping 1.1.1.1) — because VpnService is system-wide on Android and
`addRoute("0.0.0.0", 0)` routes every app's traffic, not just our UID.
Per-UID filtering without root is not possible; the feature could never work
as designed. Removed entirely per maintainer decision (option C of the
review): FirewallVpnService, vpn/ package, firewallEnabled setting, Settings
toggle, VPN permission flow in MainViewModel/MainActivity, manifest service
declaration + FOREGROUND_SERVICE_DATA_SYNC permission, and the
FirewallStartFailure fail state. The container has network access; the
sandbox to app-private storage remains the isolation boundary. ADR-007 text
above records the reversal rationale.
