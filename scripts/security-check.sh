#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

echo "[security] Running local security checks in ${ROOT}"

run() {
  local name="$1"; shift
  echo "==> ${name}"
  if ! "$@"; then
    echo "[FAIL] ${name}" >&2
    fail=1
  fi
}

if command -v gitleaks >/dev/null 2>&1; then
  run "gitleaks secret scan" gitleaks detect --source "${ROOT}" --no-git --redact
else
  echo "[warn] gitleaks not found. Install locally or enforce in CI."
fi

if command -v osv-scanner >/dev/null 2>&1; then
  run "OSV dependency scan" osv-scanner scan --recursive "${ROOT}"
else
  echo "[warn] osv-scanner not found. Install locally or enforce in CI."
fi

if [[ -f "${ROOT}/pom.xml" ]]; then
  if [[ -x "${ROOT}/mvnw" ]]; then
    run "maven dependency audit/check" bash -lc "cd \"${ROOT}\" && ./mvnw -q -DskipTests verify"
  elif command -v mvn >/dev/null 2>&1; then
    run "maven dependency audit/check" bash -lc "cd \"${ROOT}\" && mvn -q -DskipTests verify"
  fi
fi

if [[ "${fail}" -ne 0 ]]; then
  echo "[security] One or more security checks failed." >&2
  exit 1
fi

echo "[security] OK or warnings only for missing local tools"
