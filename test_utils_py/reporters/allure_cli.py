"""Manage pinned Allure 3 CLI installation, local result auto-collection, and HTML generation."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Iterable, List, Optional, Sequence, Tuple

from test_utils_py import REPO_ROOT
from test_utils_py.reporters.junit_converter import convert_junit_xml
from test_utils_py.reporters.merger import merge_results

ALLURE_VERSION = "3.33.0"
MIN_NODE_MAJOR = 22
MIN_NODE_MINOR = 18


def parse_node_version(raw: str) -> Tuple[int, int]:
    cleaned = raw.strip().lstrip("v")
    parts = cleaned.split(".")
    if len(parts) < 2:
        raise ValueError(f"Unrecognized Node.js version string: {raw!r}")
    return int(parts[0]), int(parts[1])


def check_node() -> None:
    """Verify Node.js >= MIN_NODE_MAJOR.MIN_NODE_MINOR.0 and npm are available on PATH."""
    if shutil.which("node") is None or shutil.which("npm") is None:
        raise RuntimeError(
            f"Node.js >= {MIN_NODE_MAJOR}.{MIN_NODE_MINOR}.0 and npm are required to run Allure {ALLURE_VERSION}.\n"
            "Install Node.js 22+ (for example via nvm, fnm, mise, or https://nodejs.org/) and re-run."
        )
    proc = subprocess.run(["node", "-v"], check=True, capture_output=True, text=True)
    version_text = proc.stdout.strip().lstrip("v")
    major, minor = parse_node_version(version_text)
    if (major, minor) < (MIN_NODE_MAJOR, MIN_NODE_MINOR):
        raise RuntimeError(
            f"Node.js v{version_text} is too old for allure@{ALLURE_VERSION} "
            f"(requires >= {MIN_NODE_MAJOR}.{MIN_NODE_MINOR}.0)."
        )


def resolve_cache_dir() -> Path:
    override = os.environ.get("ALLURE_CACHE_DIR")
    if override:
        return Path(override)
    xdg_cache = os.environ.get("XDG_CACHE_HOME")
    base = Path(xdg_cache) if xdg_cache else Path.home() / ".cache"
    return base / "agentsterm-allure"


def ensure_allure_bin(cache_dir: Optional[Path] = None) -> Path:
    """Install pinned `allure@ALLURE_VERSION` into the local cache prefix if absent or outdated."""
    target_cache = cache_dir or resolve_cache_dir()
    version_file = target_cache / ".allure-version"
    allure_bin = target_cache / "node_modules" / ".bin" / "allure"

    installed_version = version_file.read_text(encoding="utf-8").strip() if version_file.is_file() else ""
    if not (allure_bin.is_file() and os.access(allure_bin, os.X_OK) and installed_version == ALLURE_VERSION):
        print(f"Installing allure@{ALLURE_VERSION} into {target_cache}...", file=sys.stderr)
        target_cache.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                "npm",
                "install",
                "--prefix",
                str(target_cache),
                "--no-save",
                "--no-audit",
                "--no-fund",
                f"allure@{ALLURE_VERSION}",
            ],
            check=True,
            stdout=sys.stderr,
        )
        version_file.write_text(f"{ALLURE_VERSION}\n", encoding="utf-8")

    return allure_bin


def _collect_local_results(repo_root: Path, staging_root: Path) -> Path:
    staged = staging_root / "allure-results"
    staged.mkdir(parents=True, exist_ok=True)
    inputs: List[Path] = []

    android_unit_dir = repo_root / "app/build/test-results/testDebugUnitTest"
    if android_unit_dir.is_dir():
        roborazzi_dir = repo_root / "app/build/outputs/roborazzi"
        unit_out = staged / "android-unit"
        convert_junit_xml(
            input_path=android_unit_dir,
            output_dir=unit_out,
            parent_suite="Android Unit Tests",
            framework="junit4",
            language="kotlin",
            roborazzi_dir=roborazzi_dir if roborazzi_dir.is_dir() else None,
            tags=["android-unit"],
        )
        inputs.append(unit_out)

    android_e2e_dir = repo_root / "app/build/outputs/androidTest-results/connected"
    if android_e2e_dir.is_dir():
        e2e_out = staged / "android-e2e"
        convert_junit_xml(
            input_path=android_e2e_dir,
            output_dir=e2e_out,
            parent_suite="Android Instrumented E2E",
            framework="android-junit",
            language="kotlin",
            tags=["android-e2e"],
        )
        inputs.append(e2e_out)

    existing_results = repo_root / "allure-results"
    if existing_results.is_dir():
        inputs.append(existing_results)

    if not inputs:
        raise RuntimeError(
            "no local test results found.\n"
            "Run tests first (e.g. gradle :app:testDebugUnitTest, or emit results into ./allure-results/) "
            "or pass --results-dir."
        )

    merged_local = staging_root / "merged"
    merge_results(input_dirs=inputs, output_dir=merged_local)
    return merged_local


def generate_report(
    *,
    results_dirs: Sequence[Path] = (),
    output_dir: Path = Path("allure-report"),
    config_file: Path = Path("allurerc.json"),
    open_browser: bool = False,
    repo_root: Path = REPO_ROOT,
) -> Path:
    """Build (and optionally open) a unified Allure 3 HTML report."""
    with tempfile.TemporaryDirectory(prefix="agentsterm-allure-report-") as tmp:
        if results_dirs:
            resolved_inputs = [str(d) for d in results_dirs]
        else:
            merged = _collect_local_results(repo_root, Path(tmp))
            resolved_inputs = [str(merged)]

        check_node()
        allure_bin = ensure_allure_bin()

        subprocess.run(
            [
                str(allure_bin),
                "generate",
                *resolved_inputs,
                "--config",
                str(config_file),
                "--output",
                str(output_dir),
                "--clean",
            ],
            cwd=str(repo_root),
            check=True,
        )

        index_html = output_dir / "index.html"
        print(f"Allure 3 report generated at: {index_html}")

        if open_browser:
            subprocess.run([str(allure_bin), "open", str(output_dir)], cwd=str(repo_root), check=True)

        return index_html


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate (and optionally open) a unified Allure 3 test report locally or in CI."
    )
    parser.add_argument(
        "--results-dir",
        action="append",
        default=[],
        help=(
            "Allure results directory to include (repeatable). If omitted, auto-collects local "
            "Gradle JUnit XML reports (unit + instrumented) and any existing ./allure-results/."
        ),
    )
    parser.add_argument(
        "--output",
        "-o",
        default="allure-report",
        help="Output directory for the generated report (default: allure-report)",
    )
    parser.add_argument(
        "--config",
        "-c",
        default="allurerc.json",
        help="Path to Allure 3 config file (default: allurerc.json)",
    )
    parser.add_argument(
        "--open",
        action="store_true",
        dest="open_report",
        help="Open the generated report in the default browser via `allure open`",
    )
    return parser


def main(argv: Optional[Iterable[str]] = None) -> int:
    args = build_parser().parse_args(list(argv) if argv is not None else None)
    try:
        generate_report(
            results_dirs=[Path(d) for d in args.results_dir],
            output_dir=Path(args.output),
            config_file=Path(args.config),
            open_browser=args.open_report,
        )
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as exc:
        return exc.returncode or 1
    return 0
