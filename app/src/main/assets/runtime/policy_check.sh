#!/system/bin/sh
# Emits one JSON decision for the su wrapper: granted|denied|prompt
# Arguments: UID, CALLER_NAME, POLICY_JSON
set -eu
UID_="$1"
CALLER="$2"
POLICY="$3"

DECISION="$(grep -o "\"${UID_}\"[^}]*" "${POLICY}" | head -n1 | grep -o 'granted\|denied' || true)"
if [ -n "${DECISION}" ]; then
    echo "${DECISION}"
    exit 0
fi
# No explicit rule: fall back to prompt for interactive resolution.
echo prompt
