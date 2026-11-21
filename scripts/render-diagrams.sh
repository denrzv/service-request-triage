#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIAG_DIR="${ROOT}/diagrams"
OUT_DIR="${DIAG_DIR}/out"
TOOLS_DIR="${ROOT}/tools"
PLANTUML_JAR="${TOOLS_DIR}/plantuml.jar"

TICKET="${1:-}"
MODE="${2:-}"
FORCE_ARGS=()
if [[ "${MODE}" == "--force" || "${TICKET}" == "--force" ]]; then
  FORCE_ARGS+=("--force")
  if [[ "${TICKET}" == "--force" ]]; then
    TICKET=""
  fi
fi

if [[ ! -d "${DIAG_DIR}" ]]; then
  echo "[diagrams] No diagrams/ directory. Skipping."
  exit 0
fi

if [[ ! -x "${ROOT}/scripts/diagram-impact-check.py" ]]; then
  echo "[diagrams] Missing scripts/diagram-impact-check.py" >&2
  exit 2
fi

mapfile_compatible_list() {
  python3 "${ROOT}/scripts/diagram-impact-check.py" --ticket "${TICKET}" --list-files "${FORCE_ARGS[@]}"
}

if ! FILES="$(mapfile_compatible_list)"; then
  python3 "${ROOT}/scripts/diagram-impact-check.py" --ticket "${TICKET}" "${FORCE_ARGS[@]}" || true
  echo "[diagrams] Diagram impact check failed." >&2
  exit 1
fi
if [[ -z "${FILES}" ]]; then
  python3 "${ROOT}/scripts/diagram-impact-check.py" --ticket "${TICKET}" "${FORCE_ARGS[@]}"
  echo "[diagrams] No diagram rendering required by current plan/diff."
  exit 0
fi

mkdir -p "${OUT_DIR}"

echo "[diagrams] Rendering selected PlantUML diagram source(s):"
printf '%s\n' "${FILES}" | sed 's/^/  - /'

render_one() {
  local rel="$1"
  local file="${ROOT}/${rel}"
  if [[ ! -f "${file}" ]]; then
    echo "[diagrams] Source file not found: ${rel}" >&2
    exit 2
  fi

  if command -v plantuml >/dev/null 2>&1; then
    plantuml -tsvg -o "${OUT_DIR}" "${file}"
  elif [[ -f "${PLANTUML_JAR}" ]]; then
    if ! command -v dot >/dev/null 2>&1; then
      echo "[diagrams] graphviz 'dot' not found. Install graphviz." >&2
      exit 2
    fi
    java -jar "${PLANTUML_JAR}" -tsvg -o "${OUT_DIR}" "${file}"
  else
    echo "[diagrams] No PlantUML renderer found. Install plantuml or add tools/plantuml.jar." >&2
    exit 3
  fi
}

while IFS= read -r rel; do
  [[ -z "${rel}" ]] && continue
  render_one "${rel}"
done <<< "${FILES}"

echo "[diagrams] Rendered SVGs under ${OUT_DIR} or PlantUML's resolved output path."
