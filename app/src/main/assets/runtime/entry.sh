#!/system/bin/sh
# Runs INSIDE the container (via proot). Starts the Android userspace.
set -eu

export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin
export LD_LIBRARY_PATH=/system/lib64:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data

# Prepare writable dirs that proot maps from the host side
mkdir -p /data /sdcard /tmp

# Init-ish bootstrap for the container userspace.
/system/bin/linkerconfig /linkerconfig 2>/dev/null || true

# Start zygote (primary + secondary if present). zygote is started with the
# wrapper-based Vector injection already patched into the rootfs
# (see vector/deploy_into_rootfs.sh).
/system/bin/app_process64 /system/bin --zygote --start-system-server &
ZYGOTE_PID=$!

# Keep PID 1 semantics: wait for zygote to exit, then shut down cleanly.
wait "${ZYGOTE_PID}"
