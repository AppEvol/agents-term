#!/usr/bin/env bash
# Generate (and optionally open) a unified Allure 3 test report locally or in CI.
#
# Installs pinned `allure@3.33.0` into a local cache prefix so neither `package.json`
# nor global npm installs are required. Requires Node.js >= 22.18.0 and npm.
#
# Usage:
#   scripts/allure-report.sh                          # auto-collect local results -> allure-report/
#   scripts/allure-report.sh --open                   # generate and open in browser
#   scripts/allure-report.sh --results-dir dir1 ...   # generate from explicit result dir(s)
#   scripts/allure-report.sh --output out/ --config allurerc.json
set -euo pipefail

ALLURE_VERSION="3.33.0"
MIN_NODE_MAJOR=22
MIN_NODE_MINOR=18

usage() {
  cat <<'EOF'
Usage: scripts/allure-report.sh [options]

Options:
  --results-dir <dir>   Allure results directory to include (repeatable).
                        If omitted, auto-collects local Gradle JUnit XML reports
                        (unit + instrumented) and any existing ./allure-results/.
  --output <dir>        Output directory for the generated report (default: allure-report)
  --config <file>       Path to Allure 3 config file (default: allurerc.json)
  --open                Open the generated report in the default browser via `allure open`
  -h, --help            Show this help message
EOF
}

results_dirs=()
output_dir="allure-report"
config_file="allurerc.json"
open_report=false

while [ $# -gt 0 ]; do
  case "$1" in
    --results-dir)
      [ $# -ge 2 ] || { echo "error: --results-dir requires a value" >&2; exit 2; }
      results_dirs+=("$2")
      shift 2
      ;;
    --output|-o)
      [ $# -ge 2 ] || { echo "error: --output requires a value" >&2; exit 2; }
      output_dir="$2"
      shift 2
      ;;
    --config|-c)
      [ $# -ge 2 ] || { echo "error: --config requires a value" >&2; exit 2; }
      config_file="$2"
      shift 2
      ;;
    --open)
      open_report=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

check_node() {
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "error: Node.js >= ${MIN_NODE_MAJOR}.${MIN_NODE_MINOR}.0 and npm are required to run Allure ${ALLURE_VERSION}." >&2
    echo "Install Node.js 22+ (for example via nvm, fnm, mise, or https://nodejs.org/) and re-run." >&2
    exit 1
  fi

  local version major minor
  version="$(node -v | sed 's/^v//')"
  major="${version%%.*}"
  minor="${version#*.}"
  minor="${minor%%.*}"
  if [ "$major" -lt "$MIN_NODE_MAJOR" ] || { [ "$major" -eq "$MIN_NODE_MAJOR" ] && [ "$minor" -lt "$MIN_NODE_MINOR" ]; }; then
    echo "error: Node.js v${version} is too old for allure@${ALLURE_VERSION} (requires >= ${MIN_NODE_MAJOR}.${MIN_NODE_MINOR}.0)." >&2
    exit 1
  fi
}

ensure_allure_bin() {
  local cache_dir version_file allure_bin
  cache_dir="${ALLURE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/agentsterm-allure}"
  version_file="$cache_dir/.allure-version"
  allure_bin="$cache_dir/node_modules/.bin/allure"

  if [ ! -x "$allure_bin" ] || [ ! -f "$version_file" ] || [ "$(cat "$version_file")" != "$ALLURE_VERSION" ]; then
    echo "Installing allure@${ALLURE_VERSION} into ${cache_dir}..." >&2
    mkdir -p "$cache_dir"
    npm install \
      --prefix "$cache_dir" \
      --no-save \
      --no-audit \
      --no-fund \
      "allure@${ALLURE_VERSION}" >&2
    printf '%s\n' "$ALLURE_VERSION" > "$version_file"
  fi

  printf '%s\n' "$allure_bin"
}

tmp_dir=""
cleanup() {
  if [ -n "$tmp_dir" ] && [ -d "$tmp_dir" ]; then
    rm -rf "$tmp_dir"
  fi
}
trap cleanup EXIT

if [ ${#results_dirs[@]} -eq 0 ]; then
  tmp_dir="$(mktemp -d)"
  staged="$tmp_dir/allure-results"
  mkdir -p "$staged"
  found_any=false

  if [ -d "app/build/test-results/testDebugUnitTest" ]; then
    roborazzi_args=()
    if [ -d "app/build/outputs/roborazzi" ]; then
      roborazzi_args=(--roborazzi-dir "app/build/outputs/roborazzi")
    fi
    python3 scripts/allure_results.py junit \
      --input "app/build/test-results/testDebugUnitTest" \
      --output "$staged/android-unit" \
      --parent-suite "Android Unit Tests" \
      --framework "junit4" \
      --language "kotlin" \
      --tag "android-unit" \
      "${roborazzi_args[@]}"
    found_any=true
  fi

  if [ -d "app/build/outputs/androidTest-results/connected" ]; then
    python3 scripts/allure_results.py junit \
      --input "app/build/outputs/androidTest-results/connected" \
      --output "$staged/android-e2e" \
      --parent-suite "Android Instrumented E2E" \
      --framework "android-junit" \
      --language "kotlin" \
      --tag "android-e2e"
    found_any=true
  fi

  if [ -d "allure-results" ]; then
    results_dirs+=("allure-results")
    found_any=true
  fi

  if ! $found_any; then
    echo "error: no local test results found." >&2
    echo "Run tests first (e.g. gradle :app:testDebugUnitTest, or emit results into ./allure-results/) or pass --results-dir." >&2
    exit 1
  fi

  merged_local="$tmp_dir/merged"
  merge_args=()
  [ -d "$staged/android-unit" ] && merge_args+=(--input "$staged/android-unit")
  [ -d "$staged/android-e2e" ] && merge_args+=(--input "$staged/android-e2e")
  for d in "${results_dirs[@]}"; do
    merge_args+=(--input "$d")
  done
  python3 scripts/allure_results.py merge "${merge_args[@]}" --output "$merged_local"
  results_dirs=("$merged_local")
fi

check_node
allure_bin="$(ensure_allure_bin)"

"$allure_bin" generate "${results_dirs[@]}" \
  --config "$config_file" \
  --output "$output_dir" \
  --clean

echo "Allure 3 report generated at: ${output_dir}/index.html"

if $open_report; then
  "$allure_bin" open "$output_dir"
fi
