#!/usr/bin/env bash
#
# issue-license.sh - issue a signed askdata license.
#
# All license fields are supplied from the environment - there are NO built-in
# default values (the caller must provide real values). PRODUCT defaults to
# "askdata" only because it is the fixed product identifier.
#
# This script is a thin wrapper around issue_license.py (shared with the
# Windows issue-license.bat). All validation and signing live in that file so
# the signing logic stays identical across platforms.
#
# Usage:
#   SIGN_KEY=... \
#     CORPORATION=... VERSION=... SERIAL_NO=... EXPIRES_AT=... \
#     COUNT=... EDITION=... ISV=... REMARK=... \
#     [PRODUCT=askdata] [OUT=out.json] \
#     ./issue-license.sh [out.json]
#
# Required environment variables:
#   SIGN_KEY       HMAC signing key (same as askdata.license.sign-key)
#   CORPORATION    licensee name (授权给)
#   VERSION        product version (MUST equal askdata.app.version, else upload rejects)
#   SERIAL_NO      license serial number (序列号)
#   EXPIRES_AT     unix epoch seconds; 0 = permanent (no expiry)
#   COUNT          authorized user/resource count
#   EDITION        edition name (e.g. enterprise)
#   ISV            ISV name
#   REMARK         free-form remark (备注)
set -euo pipefail

# --- 环境检查：执行前确认 python3（或 python）可用 ---
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "error: python3 (or python) is required but was not found in PATH" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$PY" "${SCRIPT_DIR}/issue_license.py" "$@"
