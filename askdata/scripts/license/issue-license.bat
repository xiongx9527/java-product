@echo off
REM ============================================================================
REM issue-license.bat - issue a signed askdata license (Windows)
REM
REM All license fields are supplied from environment variables - NO built-in
REM default values (the caller must provide real values). PRODUCT defaults to
REM "askdata" only because it is the fixed product identifier.
REM
REM This batch file delegates all validation and signing to issue_license.py
REM (shared with the Linux/Mac issue-license.sh), so the signing logic is
REM identical across platforms.
REM
REM Usage (set the variables first, then run):
REM   set SIGN_KEY=...
REM   set CORPORATION=...
REM   set VERSION=...
REM   set SERIAL_NO=...
REM   set EXPIRES_AT=...
REM   set COUNT=...
REM   set EDITION=...
REM   set ISV=...
REM   set REMARK=...
REM   REM optional:
REM   set PRODUCT=askdata
REM   set OUT=out.json
REM   issue-license.bat [out.json]
REM ============================================================================

setlocal enabledelayedexpansion

REM signing key: SIGN_KEY or ASKDATA_LICENSE_SIGN_KEY (replicate the .py fallback)
if not defined SIGN_KEY (
  if defined ASKDATA_LICENSE_SIGN_KEY (
    set "SIGN_KEY=%ASKDATA_LICENSE_SIGN_KEY%"
  )
)

REM required fields: no built-in defaults, must be supplied externally
set "REQUIRED=SIGN_KEY CORPORATION VERSION SERIAL_NO EXPIRES_AT COUNT EDITION ISV REMARK"
set "MISSING="
for %%v in (%REQUIRED%) do (
  if not defined %%v (
    set "MISSING=!MISSING! %%v"
  )
)
if defined MISSING (
  echo error: missing required environment variables:%MISSING% 1>&2
  exit /b 1
)

REM locate python (prefer python, fall back to python3)
set "PY=python"
where python >nul 2>nul
if errorlevel 1 (
  set "PY=python3"
  where python3 >nul 2>nul
  if errorlevel 1 (
    echo error: python (or python3) is required but was not found in PATH 1>&2
    exit /b 1
  )
)

"%%PY%%" "%~dp0issue_license.py" %*
if errorlevel 1 (
  exit /b 1
)
