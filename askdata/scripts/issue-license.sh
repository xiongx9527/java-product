#!/usr/bin/env bash
#
# issue-license.sh — issue a signed askdata license.
#
# The license is a self-describing JSON document:
#   {product, edition, version, corporation, count, serialNo, remark, isv,
#    expires_at, signature}
# where `signature` is the hex HMAC-SHA256 (over the canonical, key-sorted,
# whitespace-free JSON of the other fields) keyed by the configured
# `askdata.license.sign-key`. The signing canonical form matches exactly what
# com.xiongx9527.askdata.system.license.LicenseService verifies.
#
# Usage:
#   SIGN_KEY=... ./issue-license.sh [out.json]
#
# Field overrides via environment variables:
#   PRODUCT EDITION VERSION CORPORATION COUNT SERIAL_NO REMARK ISV EXPIRES_AT
#
# EXPIRES_AT is a unix epoch seconds value; 0 means "permanent" (no expiry).
set -euo pipefail

SIGN_KEY="${SIGN_KEY:-${ASKDATA_LICENSE_SIGN_KEY:-}}"
if [[ -z "${SIGN_KEY}" ]]; then
  echo "error: SIGN_KEY (or ASKDATA_LICENSE_SIGN_KEY) is required to sign a license" >&2
  exit 1
fi

OUT="${1:-}"

python3 - "${SIGN_KEY}" "${OUT}" <<'PY'
import sys, os, json, hmac, hashlib

sign_key = sys.argv[1]
out_path = sys.argv[2] if len(sys.argv) > 2 else ""

lic = {
    "product":     os.environ.get("PRODUCT", "askdata"),
    "edition":     os.environ.get("EDITION", "Enterprise"),
    "version":     os.environ.get("VERSION", "9.9.9"),
    "corporation": os.environ.get("CORPORATION", "ACME"),
    "count":       int(os.environ.get("COUNT", "50")),
    "serialNo":    os.environ.get("SERIAL_NO", "SN-0001"),
    "remark":      os.environ.get("REMARK", "issued by scripts/issue-license.sh"),
    "isv":         os.environ.get("ISV", "fit2cloud"),
    "expires_at":  int(os.environ.get("EXPIRES_AT", "0")),
}

# Canonical form: keys sorted, compact, ASCII-escaped — mirrors Jackson's
# TreeMap + writeValueAsString used by LicenseService.
canonical = json.dumps(lic, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
digest = hmac.new(sign_key.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()
lic["signature"] = digest

text = json.dumps(lic, ensure_ascii=True)
if out_path:
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    sys.stderr.write("wrote license to %s\n" % out_path)
else:
    print(text)
PY
