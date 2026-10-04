#!/system/bin/sh
# Container runtime entry. Executed on the host, inside our app-private dir.
# Env (set by host): VM_ROOTFS_DIR, VM_RUNTIME_DIR, VM_ABI
set -eu

ROOTFS="${VM_ROOTFS_DIR:?}"
RUNTIME="${VM_RUNTIME_DIR:?}"
PROOT="${RUNTIME}/bin/proot"
shift

exec "${PROOT}" \
  --rootfs="${ROOTFS}" \
  -0 \
  -w / \
  -L "${RUNTIME}/etc/resolv.conf" \
  -b /dev \
  -b /proc \
  -b /sys \
  -b "${RUNTIME}/tmp:/tmp" \
  -b "${RUNTIME}/data:/data" \
  -b "${RUNTIME}/sdcard:/sdcard" \
  /system/bin/sh /vm/entry.sh "$@"
