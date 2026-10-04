#!/system/bin/sh
# Zygote entry wrapper (replaces /system/bin/app_process64 in the rootfs at
# deploy time; the original binary is kept as app_process64.real).
# Executed INSIDE the container. Loads the Vector-built injector into zygote.
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
REAL="${DIR}/app_process64.real"

if [ -x /system/lib64/libvector_inject.so ]; then
    LD_PRELOAD="/system/lib64/libvector_inject.so${LD_PRELOAD:+:${LD_PRELOAD}}"
    export LD_PRELOAD
fi

exec "${REAL}" "$@"
