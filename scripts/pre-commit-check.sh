#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
MAVEN_PROXY_SETTINGS=""

cleanup() {
  local status=$?
  if [[ -n "${MAVEN_PROXY_SETTINGS}" && -f "${MAVEN_PROXY_SETTINGS}" ]]; then
    rm -f -- "${MAVEN_PROXY_SETTINGS}"
  fi
  return "${status}"
}

trap cleanup EXIT

echo "[checks] Running guardrails in ${ROOT}"

run() {
  local name="$1"; shift
  echo "==> ${name}"
  if ! "$@"; then
    echo "[FAIL] ${name}" >&2
    fail=1
  fi
}

run_in_dir() {
  local dir="$1"
  shift
  (
    cd "${dir}"
    "$@"
  )
}

run_maven_verify() {
  local dir="$1"
  local executable="$2"
  if [[ -n "${MAVEN_PROXY_SETTINGS}" ]]; then
    run_in_dir "${dir}" "${executable}" -q --settings "${MAVEN_PROXY_SETTINGS}" verify
  else
    run_in_dir "${dir}" "${executable}" -q verify
  fi
}

prepare_maven_proxy_settings() {
  local proxy_url="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
  if [[ -z "${proxy_url}" ]]; then
    return
  fi

  local existing_settings=""
  local maven_user_home="${MAVEN_USER_HOME:-}"
  if [[ -z "${maven_user_home}" && -n "${HOME:-}" ]]; then
    maven_user_home="${HOME}/.m2"
  fi
  if [[ -n "${maven_user_home}" && -f "${maven_user_home}/settings.xml" ]]; then
    existing_settings="${maven_user_home}/settings.xml"
  fi

  MAVEN_PROXY_SETTINGS="$(mktemp "${TMPDIR:-/tmp}/aidd-maven-settings.XXXXXX")"
  chmod 0600 "${MAVEN_PROXY_SETTINGS}"

  python3 - "${existing_settings}" "${MAVEN_PROXY_SETTINGS}" <<'PY'
import os
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

SETTINGS_NAMESPACE = "http://maven.apache.org/SETTINGS/1.0.0"
SCHEMA_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
SCHEMA_LOCATION = (
    "http://maven.apache.org/SETTINGS/1.0.0 "
    "https://maven.apache.org/xsd/settings-1.0.0.xsd"
)

proxy_url = next(
    (
        os.environ[name]
        for name in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
        if os.environ.get(name)
    ),
    "",
)
no_proxy = os.environ.get("NO_PROXY") or os.environ.get("no_proxy", "")
existing_name, output_name = sys.argv[1:]
if "://" not in proxy_url:
    proxy_url = f"http://{proxy_url}"
parsed = urllib.parse.urlsplit(proxy_url)
if parsed.scheme not in {"http", "https"} or not parsed.hostname:
    raise SystemExit("Invalid HTTP(S) proxy configuration for Maven")

try:
    proxy_port = parsed.port or (443 if parsed.scheme == "https" else 80)
except ValueError as exc:
    raise SystemExit("Invalid proxy port for Maven") from exc

if existing_name:
    tree = ET.parse(existing_name)
    root = tree.getroot()
    namespace = root.tag[1:].split("}", 1)[0] if root.tag.startswith("{") else ""
else:
    ET.register_namespace("", SETTINGS_NAMESPACE)
    ET.register_namespace("xsi", SCHEMA_NAMESPACE)
    namespace = SETTINGS_NAMESPACE
    root = ET.Element(f"{{{namespace}}}settings")
    root.set(f"{{{SCHEMA_NAMESPACE}}}schemaLocation", SCHEMA_LOCATION)
    tree = ET.ElementTree(root)


def tag(name: str) -> str:
    return f"{{{namespace}}}{name}" if namespace else name


proxies = root.find(tag("proxies"))
if proxies is None:
    proxies = ET.SubElement(root, tag("proxies"))

for old_proxy in list(proxies):
    proxy_id = old_proxy.find(tag("id"))
    if proxy_id is not None and proxy_id.text == "aidd-environment-proxy":
        proxies.remove(old_proxy)

proxy = ET.Element(tag("proxy"))
fields = {
    "id": "aidd-environment-proxy",
    "active": "true",
    "protocol": parsed.scheme,
    "host": parsed.hostname,
    "port": str(proxy_port),
}
if parsed.username is not None:
    fields["username"] = urllib.parse.unquote(parsed.username)
if parsed.password is not None:
    fields["password"] = urllib.parse.unquote(parsed.password)
if no_proxy:
    fields["nonProxyHosts"] = "|".join(
        entry.strip() for entry in no_proxy.split(",") if entry.strip()
    )

for name, value in fields.items():
    ET.SubElement(proxy, tag(name)).text = value
proxies.insert(0, proxy)

ET.indent(tree, space="  ")
tree.write(Path(output_name), encoding="utf-8", xml_declaration=True)
PY

  echo "[info] Maven will use the HTTP(S) proxy from the environment."
}

has_npm_script() {
  local dir="$1" script="$2"
  python3 - "$dir" "$script" <<'PY'
import json, sys
from pathlib import Path
pkg = Path(sys.argv[1]) / "package.json"
script = sys.argv[2]
try:
    data = json.loads(pkg.read_text())
except Exception:
    sys.exit(1)
sys.exit(0 if script in data.get("scripts", {}) else 1)
PY
}

check_dir() {
  local dir="$1" label="$2"
  if [[ -f "${dir}/pom.xml" ]]; then
    if [[ -x "${dir}/mvnw" ]]; then
      run "${label}: maven verify" run_maven_verify "${dir}" ./mvnw
    elif command -v mvn >/dev/null 2>&1; then
      run "${label}: maven verify" run_maven_verify "${dir}" mvn
    else
      echo "[FAIL] ${label}: Maven not found." >&2
      fail=1
    fi
  elif compgen -G "${dir}/build.gradle*" >/dev/null; then
    if [[ -x "${dir}/gradlew" ]]; then
      run "${label}: gradle check" bash -lc "cd \"${dir}\" && ./gradlew check"
    elif command -v gradle >/dev/null 2>&1; then
      run "${label}: gradle check" bash -lc "cd \"${dir}\" && gradle check"
    else
      echo "[FAIL] ${label}: Gradle not found." >&2
      fail=1
    fi
  elif [[ -f "${dir}/package.json" ]]; then
    local pm="npm"
    if [[ -f "${dir}/pnpm-lock.yaml" ]] && command -v pnpm >/dev/null 2>&1; then
      pm="pnpm"
    fi
    if ! command -v "${pm}" >/dev/null 2>&1; then
      echo "[FAIL] ${label}: ${pm} not found." >&2
      fail=1
      return
    fi
    if has_npm_script "${dir}" lint; then
      if [[ "${pm}" == "pnpm" ]]; then
        run "${label}: pnpm lint" bash -lc "cd \"${dir}\" && pnpm -s run lint"
      else
        run "${label}: npm lint" bash -lc "cd \"${dir}\" && npm run -s lint"
      fi
    else
      echo "[info] ${label}: no lint script."
    fi
    if has_npm_script "${dir}" typecheck; then
      if [[ "${pm}" == "pnpm" ]]; then
        run "${label}: pnpm typecheck" bash -lc "cd \"${dir}\" && pnpm -s run typecheck"
      else
        run "${label}: npm typecheck" bash -lc "cd \"${dir}\" && npm run -s typecheck"
      fi
    elif [[ -f "${dir}/tsconfig.json" ]]; then
      # TypeScript project without a dedicated typecheck script: fall back to tsc --noEmit.
      if [[ -x "${dir}/node_modules/.bin/tsc" ]]; then
        run "${label}: tsc --noEmit" bash -lc "cd \"${dir}\" && ./node_modules/.bin/tsc --noEmit"
      elif command -v tsc >/dev/null 2>&1; then
        run "${label}: tsc --noEmit" bash -lc "cd \"${dir}\" && tsc --noEmit"
      else
        echo "[FAIL] ${label}: tsconfig.json present but tsc not found." >&2
        fail=1
      fi
    fi
    if has_npm_script "${dir}" test; then
      if [[ "${pm}" == "pnpm" ]]; then
        run "${label}: pnpm test" bash -lc "cd \"${dir}\" && pnpm -s run test"
      else
        run "${label}: npm test" bash -lc "cd \"${dir}\" && npm test --silent"
      fi
    else
      echo "[info] ${label}: no test script."
    fi
  else
    echo "[info] ${label}: no known build tool in ${dir}, skipping."
  fi
}

prepare_maven_proxy_settings

if [[ -x "${ROOT}/scripts/check-dev-secrets.sh" ]]; then
  run "dev-secret leak check" bash "${ROOT}/scripts/check-dev-secrets.sh"
fi

if command -v gitleaks >/dev/null 2>&1; then
  run "gitleaks secret scan" gitleaks detect --source "${ROOT}" --no-git --redact
else
  echo "[warn] gitleaks not found. Install locally or enforce in CI."
fi

if command -v shellcheck >/dev/null 2>&1; then
  if compgen -G "${ROOT}/scripts/*.sh" >/dev/null; then
    run "shellcheck scripts" shellcheck "${ROOT}"/scripts/*.sh
  fi
else
  echo "[warn] shellcheck not found."
fi

check_dir "${ROOT}" "root"
for m in ${PROJECT_MODULES:-}; do
  if [[ -d "${ROOT}/${m}" ]]; then
    check_dir "${ROOT}/${m}" "${m}"
  else
    echo "[FAIL] configured module '${m}' not found." >&2
    fail=1
  fi
done

if [[ "${fail}" -ne 0 ]]; then
  echo "[checks] One or more checks failed." >&2
  exit 1
fi

echo "[checks] OK"
