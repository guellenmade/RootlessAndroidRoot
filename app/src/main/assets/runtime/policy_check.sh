#!/system/bin/sh
# Emits one decision for the su wrapper: granted|denied|prompt
# Arguments: UID, CALLER_NAME, POLICY_JSON
set -eu
UID_="$1"
CALLER="$2"
POLICY="$3"

# Flatten the JSON to a single line (the policy file is pretty-printed),
# then match the exact uid field. The comma after the uid value prevents
# partial matches (e.g. uid 1000 matching uid 10001).
FLAT="$(tr -d '\n' < "${POLICY}")"
ENTRY="$(echo "${FLAT}" | grep -o "\"uid\":[[:space:]]*${UID_},\"packageName\":\"[^\"]*\",\"granted\":[[:space:]]*[a-z]*" | head -n1 || true)"
if echo "${ENTRY}" | grep -q 'granted":[[:space:]]*true'; then
    echo granted
elif echo "${ENTRY}" | grep -q 'granted":[[:space:]]*false'; then
    echo denied
else
    # No explicit rule: fall back to prompt for interactive resolution.
    echo prompt
fi
