# RootlessVM — Documentation

**RootlessVM** is a free, open-source Android app that runs a complete
virtualized Android environment on an **unrooted** device. Inside that
environment you have **root** and a working **Xposed framework** (built from
the Vector source code) — *strictly inside the VM*. The host device is never
modified, never rooted, and never touched by the container.

- License: **GPL-3.0-or-later**
- No trackers, no proprietary images, no prebuilt blobs: everything is built
  from pinned source.
- Reference behavior: VMOS/F1 VM — but open, auditable, and honest about
  limitations.

---

## Table of contents

1. [Feature overview](#1-feature-overview)
2. [Architecture](#2-architecture)
3. [Module explanations](#3-module-explanations)
4. [Why proot? (Decision A)](#4-why-proot-decision-a)
5. [Display and GPU](#5-display-and-gpu)
6. [Compatibility matrix](#6-compatibility-matrix)
7. [Installation guide](#7-installation-guide)
8. [Build guide](#8-build-guide)
9. [FAQ](#9-faq)
10. [Security and privacy](#10-security-and-privacy)
11. [Known limitations](#11-known-limitations)
12. [Local testing setup (Phase 6 runbook)](#12-local-testing-setup-phase-6-runbook)
13. [Changelog](#13-changelog)

---

## 1. Feature overview

| Feature | Status |
|---|---|
| Virtualized Android on unrooted host (proot) | v1 |
| Root inside the container (su + manager UI, per-app grant/deny, logs) | v1 |
| Xposed framework inside the container (Vector @ `ddeed8c`, legacy + libxposed API) | v1 |
| Vector manager UI inside the container (module list, scope, enable/disable, logs) | v1 |
| Container apps as host-launcher shortcuts (pinned, adaptive icons) | v1 |
| In-app grid of all container apps | v1 |
| APK install into container via file picker | v1 |
| VM controls: start/stop, RAM/CPU limits, snapshots | v1 |
| Network firewall (VpnService, deny-all) | v1 |
| Software rendering (llvmpipe), GPU detection + honest reporting | v1 |
| AVF/pKVM fast path with GPU passthrough | roadmap (ADR-002) |

## 2. Architecture

```mermaid
flowchart TD
    subgraph HOST[Host Android — unrooted]
        UI[RootlessVM app\nCompose UI + MVVM]
        SVC[VmService\nforeground service]
        VPN[FirewallVpnService\nTUN, deny-all]
        SC[ShortcutSync\nrequestPinShortcut]
        DB[(JSON stores:\nsu policy, apps, modules)]
    end
    subgraph CONTAINER[proot container — app-private storage]
        ZYG[zygote\napp_process wrapper + LD_PRELOAD]
        VEC[Vector\nLSPlant + Dobby + XposedBridge]
        SU[su wrapper\npolicy from host JSON]
        APPS[container apps\nhooked by Xposed modules]
        SCR[screencap stream]
    end
    UI --> SVC
    SVC -->|proot -0| CONTAINER
    VEC --> ZYG
    ZYG --> APPS
    APPS --> SCR --> UI
    APPS -->|install events| DB --> SC
    SVC --> VPN
    SU <--> DB
end
```

Boot → hook chain (honest version, also in AGENT.md §2):

```
VM start
  -> proot -0 -r <rootfs> ... (fake root: uid 0 inside the namespace only)
  -> container Android userspace boots; zygote starts
  -> zygote entry: /system/bin/app_process64 was patched at deploy time into a
     wrapper that LD_PRELOADs the Vector-built injector, then execs the real binary
  -> injector initializes LSPlant/Dobby inside zygote and reads module config
  -> every forked app process carries the hooks
  -> Xposed modules hook apps inside the container only
```

## 3. Module explanations

| Module (repo path) | Responsibility |
|---|---|
| `app/src/main/kotlin/.../vm/` | VmService lifecycle, ProotCommandBuilder (argv construction incl. RAM/CPU limits via cgroup-like `taskset`/`ulimit` inside the container), display bridge (screencap polling), input injection, GPU detection, APK installation into the container. |
| `app/src/main/kotlin/.../vpn/` | FirewallVpnService: TUN-based deny-all for the app UID; `protect()`s host management sockets. |
| `app/src/main/kotlin/.../shortcut/` | ShortcutSync: scans container package list after installs, builds adaptive icons, pins launcher shortcuts; intent handling: boot VM if needed → open target app. |
| `app/src/main/kotlin/.../root/` | SuPolicyManager: per-app grant/deny stored as JSON consumed by the container-side `su` wrapper; request logging. |
| `app/src/main/kotlin/.../xposed/` | Container module config writer/reader: enable/disable modules, per-app scope, reading Vector's config; surface logs in manager UI. |
| `app/src/main/kotlin/.../data/` | Rootfs download (with SHA-256 verification), manifest, unpack, stores, container app scanner. |
| `app/src/main/assets/runtime/` | POSIX shell scripts executed inside/around proot: `start_container.sh`, `exec_in_container.sh`, `install_apk_in_container.sh`, `su`, `app_process_wrapper.sh`. |
| `vector/` | Vector build (`build_vector.sh`, pinned commit `ddeed8c`) + `deploy_into_rootfs.sh` (installs artifacts and zygote wrapper into the rootfs). |
| `proot/` | proot build from source per ABI. |
| `.github/workflows/` | CI: app build, Vector artifact build, releases. |
| `metadata/` | F-Droid metadata. |

## 4. Why proot? (Decision A)

Two candidate architectures were compared:

| Criterion | A: proot namespace | B: AVF/pKVM guest Android |
|---|---|---|
| Host root required | No | No |
| Min Android | 8 (project uses 8/API 26 baseline; app minSdk 28) | 13 + pKVM-enabled device |
| Device coverage | Nearly all arm64/arm/x86_64 devices | Small, growing subset |
| GPU | Software (llvmpipe); GPU passthrough N/A | GPU passthrough possible but not stable/shipping for guest Android |
| Isolation strength | Weaker (ptrace-based translation, shared kernel) | Strong (hardware-backed VM) |
| Performance | Syscall translation overhead | Near-native CPU |
| Engineering cost for v1 | Low–medium | High (per-device images, kernel requirements) |
| Root inside VM | Trivial (`proot -0`) | Needs rootfs engineering anyway |

**Decision (ADR-002):** proot is the baseline for everyone; AVF is an optional
fast path where the device supports it. v1 ships the proot path only; the AVF
path is detected (capability check) and reported honestly as unavailable.

## 5. Display and GPU

- Default renderer: **llvmpipe** (Mesa software rendering) inside the
  container; pixels reach the host via the container's `screencap`.
- On VM start the host runs **GPU detection** (Adreno / Mali / PowerVR /
  other) and reports it in the UI *for information only*: in the proot path
  the host GPU is **not** used by the container. The UI says so plainly.
- Performance reporting is honest: frame interval and proot CPU overhead are
  measured and shown; nothing is marketed as "hardware accelerated" unless it
  is.
- AVF path (roadmap): when pKVM is available, a guest Android with GPU
  passthrough can replace the proot path for supported devices.

## 6. Compatibility matrix

| Host Android | arm64-v8a | armeabi-v7a | x86_64 | Status |
|---|---|---|---|---|
| Android 8–9 (API 26–28) | ✔ (app needs API 28) | ✔ | ✔ | proot path; old hosts below API 28 unsupported by the app |
| Android 10–12 (API 29–31) | ✔ | ✔ | ✔ | proot path |
| Android 13–15 (API 33–35) | ✔ | ✔ | ✔ | proot path; AVF detected but roadmap |
| Android 16+ | ✔ | ✔ | ✔ | proot path |

| Container (rootfs) Android | Vector artifacts | Xposed APIs | Status |
|---|---|---|---|
| Android 13 (A64, AOSP/LineageOS-derived) | built from Vector `ddeed8c` for Android 13 ART | legacy + libxposed | primary target |
| other rootfs versions | must rebuild Vector artifacts for that ART | — | refused by fail state until artifacts exist |

Version cross-check: the rootfs manifest carries its Android release; the app
refuses to start a VM whose rootfs release has no matching Vector artifact set
(clean abort, dialog with reason).

## 7. Installation guide

1. Install the APK (from F-Droid metadata target or GitHub Releases).
2. On first launch the app downloads the rootfs (AOSP/LineageOS-derived),
   verifies its SHA-256, and unpacks it into app-private storage.
3. Grant the VPN permission when prompted if you want the container firewall.
4. (Optional) Pin container apps to your launcher from the in-app grid.

Storage requirement: roughly 3–4 GB free for rootfs download + unpack.

## 8. Build guide

See AGENT.md §5 for exact commands. Short version:

```bash
./gradlew :app:assembleDebug          # host app (JDK 17+, Android SDK)
vector/build_vector.sh work/          # Vector artifacts (Android NDK)
proot/build_proot.sh work/ arm64-v8a  # proot binary
```

CI (`.github/workflows/android.yml`) builds the app on every push;
`vector.yml` builds and archives the Vector artifacts; `release.yml` cuts
releases on tags.

## 9. FAQ

**Why is root only inside the VM?**
The container runs under `proot -0`: processes inside the namespace *believe*
they are root over the container filesystem, and that is true — within the
rootfs that lives in our app-private directory. The host kernel still sees our
app UID; there is no escalation path, and none is needed for the Xposed chain.
Rooting the host would be dangerous, unnecessary, and against the project's
goal.

**How does the shortcut mechanism work?**
After every APK install inside the container, the app reads the container's
package list (label + icon via the container's PackageManager), stores it in a
local DB (JSON store), converts the icon to an **Adaptive Icon**, and offers a
pinned shortcut (`ShortcutManager.requestPinShortcut`). Launching the shortcut
starts the host app, boots the VM if it is not running, and opens the target
app in a container display session. If the launcher refuses pinning, the
in-app grid still works.

**How does the Xposed framework work without Magisk?**
Normally Vector ships as a Zygisk module and needs a Magisk/KernelSU daemon.
Neither exists in our container — so we don't install Vector as a module. We
build the injection layer from Vector's source (LSPlant, Dobby, XposedBridge)
and, because we fully own the rootfs image, we patch the zygote entry at deploy
time: `/system/bin/app_process64` becomes a wrapper that LD_PRELOADs the
injector before exec'ing the real binary. Modules then hook apps forked from
zygote — inside the container only. This is a Riru-style direct load adapted
to a rootfs we control; the exact chain is documented in AGENT.md §2.

**Is the container network-isolated?**
Yes by default: a VpnService routes all of the app UID's traffic into a TUN
device and drops it. v1 is deny-all (the container has no network). Selective
rules are on the roadmap.

**Which Xposed modules work?**
Modules using the legacy Xposed API or the modern libxposed API, targeting the
container's Android version (see §6). Modules must be installed *inside* the
container. WebUI-based module management is not available (Vector 2.0 removed
WebUI).

**Why is it slow?**
proot translates syscalls in userspace, and rendering is software (llvmpipe).
The app reports measured performance instead of hiding it.

## 10. Security and privacy

- No trackers, no analytics, no proprietary components (GPL-3.0-or-later).
- The container cannot touch host data outside the app's private directory.
- The firewall denies all container network egress by default.
- Checksums (SHA-256) are mandatory for rootfs downloads; failure = clean abort.

## 11. Known limitations

See AGENT.md §8 — kept in one place so agents and humans never diverge.
Summary: proot/llvmpipe performance, deny-all firewall v1, polling-based
display, Vector artifacts tied to rootfs Android version, AVF path roadmap.

## 11a. Deep dive: the shortcut mechanism

The full pipeline, step by step:

1. **Install:** an APK is installed into the container (`ContainerSession.installApk`).
2. **Scan:** the host asks the container's PackageManager for third-party
   packages and per-package info (`pm list packages -3`, `dumpsys package`).
3. **Store:** label, version, and icon land in the JSON `ContainerAppStore`
   (ADR-003) under app-private storage.
4. **Icon conversion:** `AdaptiveIconFactory` renders the icon into a
   432×432 adaptive-icon safe zone (foreground layer), and `ShortcutSync`
   pins it via `ShortcutManager` with `Icon.createWithAdaptiveBitmap` — so
   launchers mask it correctly (circle/squircle/etc.).
5. **Pinning:** `requestPinShortcut` asks the launcher to add the shortcut;
   launchers that refuse pinning are handled gracefully (the in-app grid
   still works).
6. **Launch:** the shortcut's intent targets `ShortcutLaunchActivity`, which
   (a) starts the VM if it is not running (waiting up to 30 s for the Running
   state), (b) launches the target package inside the container, and (c)
   forwards the user to the main UI with the live container display.

Everything runs under our app UID: no host permissions beyond the
shortcut + VPN + service set declared in the manifest.

## 11b. Deep dive: Xposed without Magisk — exact chain

```
[rootfs deploy time — once]
  vector/build_vector.sh         # clone Vector @ ddeed8c, build injector + dex + manager
  vector/deploy_into_rootfs.sh   # install into rootfs:
     /system/lib64/libvector_inject.so   (LSPlant + Dobby + lspd native)
     /system/framework/vector-xposed.jar (XposedBridge classpath)
     /system/bin/app_process64 -> wrapper; original kept as app_process64.real
     /data/adb/vector/                    (module config home)

[VM boot — every start]
  proot -0 ...  -> container Android userspace boots
  zygote: /system/bin/app_process64 (our wrapper)
     sets LD_PRELOAD=/system/lib64/libvector_inject.so
     execs app_process64.real --zygote --start-system-server
  injector initializes inside zygote:
     loads vector-xposed.jar into the zygote classpath
     LSPlant/ART hooking engine armed (Dobby for inline hooks)
     module config read from /data/adb/vector (inside the rootfs)
  app forked from zygote -> hooks inherited
     legacy-API modules: IXposedHookLoadPackage et al.
     libxposed-API modules: modern API surface
     scope rules: only hooked if the module's scope includes the package
```

Key honesty points:

- There is **no Zygisk** and **no Magisk/KernelSU** in the container; the
  injection is a Riru-era-style direct load, possible only because we own
  the rootfs image (ADR-005).
- **Vector's manager UI runs inside the container** as a normal app:
  module list, per-app scope, enable/disable, logs. WebUI is not available
  (Vector 2.0 removed it).
- **Version lock:** the injector is built against the rootfs's ART. The rootfs
  carries `vector-manifest.json`; `VmController.checkCompatibility()` refuses
  to boot on mismatch (clean abort).
- **Host safety:** everything above happens inside the proot namespace over
  app-private storage. No host process is hooked, ever.

## 12. Local testing setup (Phase 6 runbook)

Full runbook: [docs/local-testing/phase6_runbook.md](docs/local-testing/phase6_runbook.md),
sample module notes: [docs/local-testing/pinned_sample_module.md](docs/local-testing/pinned_sample_module.md).

Short version (arm64, Android 13+ host recommended):

1. Build + install the debug app; launch; let the rootfs download and unpack
   (checksum-verified).
2. Install **two container apps** (e.g. the F-Droid client + one more small
   FOSS APK) via the in-app APK file picker.
3. Verify both apps appear in the in-app grid; pin their shortcuts to the
   host launcher; confirm icons are adaptive and launch into the VM.
4. Install one real Xposed module inside the container, enable it with
   scope = the target app in the Vector manager UI, restart the target app,
   and verify the hook (visible effect + Vector logs).
5. Accept `su` for one app, deny for another; check the superuser list and
   logs; confirm the host remains unrooted.

Pass criteria and negative tests (checksum mismatch, artifact version
mismatch, firewall refusal) are listed in the runbook.

## 13. Changelog

- **Phase 1:** Architecture decision (ADR-002: proot baseline, AVF optional),
  compatibility matrix, limitations; initial AGENT.md and DOCUMENTATION.md;
  GPL-3.0 LICENSE; NOTICE.
- **Phase 2:** Repo layout: Gradle skeleton, manifest, launcher resources, container runtime scripts, su wrapper, proot/Vector build scripts, README; F-Droid metadata and CI follow in Phase 4.
- **Phase 3:** Complete app source (MVVM + Compose): VM service, proot runtime, rootfs installer with checksum verification, su policy manager, Xposed module manager, firewall VPN, shortcuts with adaptive icons, in-app app grid; unit tests for fail states and version cross-check.
- **Phase 4:** CI pipeline (app build/test, Vector + proot artifact builds, tag-driven releases) and F-Droid metadata.
- **Phase 5:** DOCUMENTATION.md finalized: deep-dive sections for the
  shortcut mechanism (11a) and the exact Xposed-without-Magisk chain (11b),
  local-testing bundle under docs/local-testing/ (Phase 6 runbook + pinned
  sample module notes), expanded device matrix pointers.
- **Phase 6:** Local test setup documented (runbook + sample module pin);
  on-device execution remains device-dependent and is tracked in AGENT.md §9.

<!-- maintained with AGENT.md; see §13 changelog -->
- **Build-everything pass (post Phase 6):** First-launch "Prepare VM" flow
  (proot provisioning per ADR-009 + rootfs download from the bundled catalog
  per ADR-008, SHA-256 verified), live container display in the Home tab with
  tap/back input injection and measured fps, rewritten deny-all firewall pump.
  Rootfs catalog asset added; SHA-256 fields are placeholders until the first
  rootfs image is built and published (see AGENT.md §9 open tasks).
- **Download-everything button:** first launch now has a single button that
  provisions proot, downloads + verifies + unpacks the rootfs, and downloads +
  deploys the Vector (Xposed) artifacts into the rootfs, with live step
  progress and clean-abort fail dialogs.
- **v0.1.0-alpha:** first release published via CI (unsigned APK attached);
  catalog artifacts remain placeholders — download flow aborts cleanly at
  checksum until real artifacts are published.
- **v0.1.0-alpha2:** signing fix — release APKs are always signed
  (debug-signing fallback when no release keystore is configured); broken
  unsigned asset removed from v0.1.0-alpha.
- **v0.1.0-alpha3:** fixed false "unsupported ABI" error — real cause
  (missing runtime artifacts) now reported accurately; ABI detection via
  Build.SUPPORTED_ABIS.
