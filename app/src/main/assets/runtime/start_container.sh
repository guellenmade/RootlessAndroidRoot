#!/system/bin/sh
# Container runtime entry. Executed on the host, inside our app-private dir.
# Env (set by host): VM_ROOTFS_DIR, VM_RUNTIME_DIR, VM_ABI
#
# NOTE: This script is a legacy/alternative launch path. The primary launch
# goes through ProotCommandBuilder (Kotlin). Kept in sync for manual use.
set -eu

ROOTFS="${VM_ROOTFS_DIR:?}"
RUNTIME="${VM_RUNTIME_DIR:?}"
PROOT="${RUNTIME}/bin/proot"
shift

# NOTE: -L is NOT a valid proot option (causes SIGABRT). Removed.
# Bind mounts must match ContainerPaths: base/{tmp,data,sdcard} and runtimeDir at /vm.
exec "${PROOT}" \
  -r "${ROOTFS}" \
  -0 \
  -w / \
  -b /dev \
  -b /proc \
  -b /sys \
  -b "${VM_TMP_DIR:-${RUNTIME}/../tmp}:/tmp" \
  -b "${VM_DATA_DIR:-${RUNTIME}/../data}:/data" \
  -b "${VM_SDCARD_DIR:-${RUNTIME}/../sdcard}:/sdcard" \
  -b "${RUNTIME}:/vm" \
  /system/bin/sh /vm/entry.sh "$@"
