# Phase 6 runbook — local testing

Goal: prove, on one physical device, that (a) two apps installed inside the
container appear as working launcher shortcuts, and (b) one real Xposed
module hooks a target app inside the container.

## Preconditions

- Host: arm64, Android 13+; >= 4 GB free storage.
- Debug APK built from this repo (`gradle :app:assembleDebug`) and installed.
- Vector artifacts built for the rootfs Android version and deployed into the
  rootfs (`vector/build_vector.sh`, `vector/deploy_into_rootfs.sh`) — the app
  refuses to start the VM otherwise (fail state, by design).

## Steps

1. **First launch:** open the app; download the rootfs (checksum-verified);
   confirm the "VM not installed" state disappears.
2. **Start VM:** Home tab -> Start VM. Grant the VPN permission when asked
   (firewall mode). Expect: notification "VM running…", no fail dialog.
3. **Install two container apps:** Apps tab -> Install APK -> pick two small
   FOSS APKs (e.g. the F-Droid client, and "Material Files" or any simple
   app). After each install, the app rescans the container PackageManager
   and stores label/icon.
4. **Verify shortcuts:** in the Apps grid, tap an app tile -> "pin shortcut"
   request; confirm the launcher pins it with an adaptive icon. Launch the
   shortcut from the launcher: the host app must open, boot the VM if needed,
   and start the target app inside the container (visible via the display
   session). Repeat for the second app.
5. **Install one real Xposed module inside the container:** pick a
   legacy-API module built for the container's Android version (see
   `pinned_sample_module.md`), install it into the container, open the Vector
   manager (inside the container), enable the module with scope = target
   app, force-stop and restart the target app inside the container.
6. **Verify the hook:** observe the module's effect on the target app
   (e.g. a string replaced, an ad hidden, a debug toast) and check the
   Vector log inside the container shows the module load for that process.
7. **Root check:** from a container shell, run `su` for one app (allow) and
   another (deny); verify the Superuser tab lists both and the log shows the
   decisions. The host must remain unmodified (no su on the host).

## Pass criteria

- Both shortcuts launch the correct container app via the VM.
- The module hook is observably active inside the container only.
- Vector log shows the module load; su log shows both decisions.
- Host device: no root, no persistent changes (verified with a reboot).

## Fail states expected to trigger cleanly (negative tests)

- Corrupt the rootfs URL/checksum -> checksum-mismatch dialog, partial file
  deleted.
- Start VM with mismatched Vector artifacts -> version-incompatible dialog,
  VM does not start.
- Disable firewall VPN permission -> VM refuses to start in firewall mode.
