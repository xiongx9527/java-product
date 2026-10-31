#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# issue_license.py - sign an askdata license.
#
# The license is a self-describing JSON document:
#   {product, edition, version, corporation, count, serialNo, remark, isv,
#    expires_at, signature}
# where 'signature' is the hex HMAC-SHA256 (over the canonical, key-sorted,
# whitespace-free JSON of the other fields) keyed by the configured
# 'askdata.license.sign-key'. The canonical form matches exactly what
# com.xiongx9527.askdata.system.license.LicenseService verifies.
#
# All license fields are supplied from the environment - there are NO built-in
# default values (the caller must provide real values). PRODUCT defaults to
# "askdata" only because it is the fixed product identifier.
#
# Usage:
#   SIGN_KEY=... \
#     CORPORATION=... VERSION=... SERIAL_NO=... EXPIRES_AT=... \
#     COUNT=... EDITION=... ISV=... REMARK=... \
#     [PRODUCT=askdata] [OUT=out.json] \
#     python3 issue_license.py [out.json]
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

import sys

if sys.version_info < (3, 6):
    sys.stderr.write("error: python 3.6 or newer is required, found " + sys.version.split()[0] + "\n")
    sys.exit(1)

import os
import json
import hmac
import hashlib


def fail(msg):
    sys.stderr.write("error: " + msg + "\n")
    sys.exit(1)


def main():
    # signing key: SIGN_KEY or ASKDATA_LICENSE_SIGN_KEY, never defaulted
    sign_key = os.environ.get("SIGN_KEY") or os.environ.get("ASKDATA_LICENSE_SIGN_KEY")
    if not sign_key:
        fail("SIGN_KEY (or ASKDATA_LICENSE_SIGN_KEY) is required to sign a license")

    # required fields: no built-in defaults, must be supplied externally
    REQUIRED = ["CORPORATION", "VERSION", "SERIAL_NO", "EXPIRES_AT",
                "COUNT", "EDITION", "ISV", "REMARK"]
    missing = [v for v in REQUIRED if not os.environ.get(v)]
    if missing:
        fail("missing required environment variables: " + ", ".join(missing))

    try:
        count = int(os.environ["COUNT"])
    except ValueError:
        fail("COUNT must be an integer, got: " + os.environ["COUNT"])

    try:
        expires_at = int(os.environ["EXPIRES_AT"])
    except ValueError:
        fail("EXPIRES_AT must be an integer (unix epoch seconds), got: " + os.environ["EXPIRES_AT"])

    lic = {
        "product":     os.environ.get("PRODUCT", "askdata"),
        "edition":     os.environ["EDITION"],
        "version":     os.environ["VERSION"],
        "corporation": os.environ["CORPORATION"],
        "count":       count,
        "serialNo":    os.environ["SERIAL_NO"],
        "remark":      os.environ["REMARK"],
        "isv":         os.environ["ISV"],
        "expires_at":  expires_at,
    }

    # Canonical form: keys sorted, compact, ASCII-escaped, mirrors Jackson's
    # TreeMap + writeValueAsString used by LicenseService.
    canonical = json.dumps(lic, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    digest = hmac.new(sign_key.encode("utf-8"),
                      canonical.encode("utf-8"),
                      hashlib.sha256).hexdigest()
    lic["signature"] = digest

    text = json.dumps(lic, ensure_ascii=False)

    out_path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("OUT", "")
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        sys.stderr.write("wrote license to " + out_path + "\n")
    else:
        sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
