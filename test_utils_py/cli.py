"""Unified CLI for Allure 3 result emission, JUnit/Cargo conversion, merging, summaries, and badges."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

from test_utils_py.analytics.badges import generate_badges
from test_utils_py.reporters.allure_cli import generate_report
from test_utils_py.reporters.allure_model import parse_key_values
from test_utils_py.reporters.ci_report import run_ci_report
from test_utils_py.reporters.junit_converter import convert_junit_xml
from test_utils_py.reporters.markdown import build_markdown_summary, write_summary_outputs
from test_utils_py.reporters.merger import merge_results
from test_utils_py.runners.android_e2e import run_android_e2e
from test_utils_py.runners.cargo_runner import parse_cargo_test_output, run_cargo_tests
from test_utils_py.runners.unittest_runner import run_unittest_suite


def _cmd_unittest(args: argparse.Namespace) -> int:
    params = [{"name": k, "value": v} for k, v in parse_key_values(args.param, "--param")]
    completion_file = Path(args.completion_file) if args.completion_file else None
    return run_unittest_suite(
        start_dir=args.start_dir,
        output_dir=Path(args.output),
        parent_suite=args.parent_suite,
        pattern=args.pattern,
        top_level_dir=args.top_level_dir,
        suite_override=args.suite,
        variant=args.variant,
        parameters=params,
        tags=args.tag,
        verbosity=args.verbosity,
        test_id=args.test_id,
        completion_file=completion_file,
    )


def _cmd_junit(args: argparse.Namespace) -> int:
    params = [{"name": k, "value": v} for k, v in parse_key_values(args.param, "--param")]
    roborazzi_dir = Path(args.roborazzi_dir) if args.roborazzi_dir else None
    count = convert_junit_xml(
        input_path=Path(args.input),
        output_dir=Path(args.output),
        parent_suite=args.parent_suite,
        framework=args.framework,
        language=args.language,
        roborazzi_dir=roborazzi_dir,
        suite_override=args.suite,
        variant=args.variant,
        parameters=params,
        tags=args.tag,
    )
    print(f"Converted {count} JUnit test result(s) into {args.output}")
    return 0


def _cmd_cargo(args: argparse.Namespace) -> int:
    output_dir = Path(args.output)
    if args.log_file:
        text = Path(args.log_file).read_text(encoding="utf-8", errors="replace")
        count = parse_cargo_test_output(
            output_text=text,
            output_dir=output_dir,
            parent_suite=args.parent_suite,
            exit_code=args.exit_code,
            tags=args.tag,
        )
        print(f"Parsed {count} Rust test result(s) into {output_dir}")
        return args.exit_code

    cmd = list(args.command)
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        print("error: cargo subcommand requires -- <command...> or --log-file", file=sys.stderr)
        return 2

    code, _ = run_cargo_tests(
        cmd,
        output_dir,
        cwd=args.cwd,
        parent_suite=args.parent_suite,
        tags=args.tag,
    )
    return code


def _cmd_merge(args: argparse.Namespace) -> int:
    env_pairs = parse_key_values(args.env, "--env")
    executor: Optional[Dict[str, Any]] = None
    if any((args.executor_name, args.executor_url, args.build_name, args.build_url, args.build_order)):
        executor = {
            "name": args.executor_name or "CI",
            "type": args.executor_type or "github",
            "url": args.executor_url,
            "buildName": args.build_name,
            "buildUrl": args.build_url,
            "buildOrder": args.build_order,
        }
    count = merge_results(
        input_dirs=[Path(p) for p in args.input],
        output_dir=Path(args.output),
        env_pairs=env_pairs,
        executor=executor,
    )
    print(f"Merged {count} Allure test result(s) into {args.output}")
    return 0


def _cmd_summary(args: argparse.Namespace) -> int:
    markdown, count = build_markdown_summary(
        Path(args.results_dir),
        artifact_name=args.artifact_name,
        report_url=args.report_url or "",
        dashboard_url=args.dashboard_url or "",
    )
    sys.stdout.write(markdown)
    write_summary_outputs(
        markdown,
        count,
        step_summary=Path(args.step_summary) if args.step_summary else None,
        github_output=Path(args.github_output) if args.github_output else None,
    )
    return 0


def _cmd_badges(args: argparse.Namespace) -> int:
    metrics = generate_badges(
        Path(args.results_dir),
        Path(args.output_dir),
        commit_sha=args.commit or "",
        run_url=args.run_url or "",
        dashboard_url=args.dashboard_url or "",
    )
    print(f"Generated SVG badges and metrics.json ({metrics['passed']}/{metrics['total']} passed) in {args.output_dir}")
    return 0


def _cmd_report(args: argparse.Namespace) -> int:
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
    return 0


def _cmd_ci_report(_args: argparse.Namespace) -> int:
    return run_ci_report()


def _cmd_android_e2e(args: argparse.Namespace) -> int:
    return run_android_e2e(args.gradle_args)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Allure 3 test-result and runner CLI for AgentsTerm")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    p_unit = subparsers.add_parser("unittest", help="Run Python unittest discovery and emit Allure JSON")
    p_unit.add_argument("--start-dir", required=True, help="Directory to discover tests from")
    p_unit.add_argument("--pattern", default="test*.py", help="Filename pattern (default: test*.py)")
    p_unit.add_argument("--top-level-dir", default=None, help="Top-level project directory for imports")
    p_unit.add_argument("--output", required=True, help="Allure results output directory")
    p_unit.add_argument("--parent-suite", required=True, help="Top-level Allure parentSuite label")
    p_unit.add_argument("--suite", default=None, help="Override suite label (e.g., matrix label)")
    p_unit.add_argument("--variant", default=None, help="Unique matrix variant prefix for fullName/historyId")
    p_unit.add_argument("--param", action="append", default=[], help="Test parameter KEY=VALUE (repeatable)")
    p_unit.add_argument("--tag", action="append", default=[], help="Extra Allure tag label (repeatable)")
    p_unit.add_argument("--verbosity", type=int, default=2, help="unittest verbosity (default: 2)")
    p_unit.add_argument("--test-id", help="Run a single fully qualified unittest method")
    p_unit.add_argument("--completion-file", help="Create this marker only after a complete unittest run")
    p_unit.set_defaults(func=_cmd_unittest)

    p_junit = subparsers.add_parser("junit", help="Convert JUnit XML files to Allure JSON")
    p_junit.add_argument("--input", required=True, help="JUnit XML file or directory")
    p_junit.add_argument("--output", required=True, help="Allure results output directory")
    p_junit.add_argument("--parent-suite", required=True, help="Top-level Allure parentSuite label")
    p_junit.add_argument("--suite", default=None, help="Override suite label")
    p_junit.add_argument("--framework", default="junit4", help="Framework label (default: junit4)")
    p_junit.add_argument("--language", default="kotlin", help="Language label (default: kotlin)")
    p_junit.add_argument("--roborazzi-dir", default=None, help="Optional Roborazzi diff directory")
    p_junit.add_argument("--variant", default=None, help="Unique matrix variant prefix")
    p_junit.add_argument("--param", action="append", default=[], help="Test parameter KEY=VALUE (repeatable)")
    p_junit.add_argument("--tag", action="append", default=[], help="Extra Allure tag label (repeatable)")
    p_junit.set_defaults(func=_cmd_junit)

    p_cargo = subparsers.add_parser("cargo", help="Run cargo test (or parse log) and emit Allure JSON")
    p_cargo.add_argument("--output", required=True, help="Allure results output directory")
    p_cargo.add_argument("--parent-suite", default="Rust VM Daemon", help="Top-level Allure parentSuite label")
    p_cargo.add_argument("--cwd", default=None, help="Working directory for cargo command")
    p_cargo.add_argument("--log-file", default=None, help="Parse an existing cargo test log instead of running")
    p_cargo.add_argument("--exit-code", type=int, default=0, help="Exit code to assume with --log-file")
    p_cargo.add_argument("--tag", action="append", default=[], help="Extra Allure tag label (repeatable)")
    p_cargo.add_argument("command", nargs=argparse.REMAINDER, help="Cargo command after `--`")
    p_cargo.set_defaults(func=_cmd_cargo)

    p_merge = subparsers.add_parser("merge", help="Merge Allure result directories and write metadata")
    p_merge.add_argument("--input", action="append", required=True, help="Input directory (repeatable)")
    p_merge.add_argument("--output", required=True, help="Merged output directory")
    p_merge.add_argument("--env", action="append", default=[], help="environment.properties KEY=VALUE")
    p_merge.add_argument("--executor-name", default=None)
    p_merge.add_argument("--executor-type", default="github")
    p_merge.add_argument("--executor-url", default=None)
    p_merge.add_argument("--build-name", default=None)
    p_merge.add_argument("--build-url", default=None)
    p_merge.add_argument("--build-order", default=None)
    p_merge.set_defaults(func=_cmd_merge)

    p_summary = subparsers.add_parser("summary", help="Render Markdown summary from merged Allure results")
    p_summary.add_argument("--results-dir", required=True, help="Merged Allure results directory")
    p_summary.add_argument("--artifact-name", default="allure-report", help="Uploaded artifact name")
    p_summary.add_argument("--report-url", default="", help="Workflow run URL")
    p_summary.add_argument("--dashboard-url", default="", help="Hosted Allure 3 GitHub Pages URL")
    p_summary.add_argument("--step-summary", default=os.environ.get("GITHUB_STEP_SUMMARY", ""))
    p_summary.add_argument("--github-output", default="")
    p_summary.set_defaults(func=_cmd_summary)

    p_badges = subparsers.add_parser("badges", help="Generate self-hosted SVG metric badges and metrics.json")
    p_badges.add_argument("--results-dir", required=True, help="Merged Allure results directory")
    p_badges.add_argument("--output-dir", required=True, help="Report output directory (writes badges/*.svg)")
    p_badges.add_argument("--commit", default="", help="Commit SHA associated with the run")
    p_badges.add_argument("--run-url", default="", help="Workflow run URL")
    p_badges.add_argument("--dashboard-url", default="", help="Hosted Allure 3 GitHub Pages URL")
    p_badges.set_defaults(func=_cmd_badges)

    p_report = subparsers.add_parser("report", help="Generate (and optionally open) an Allure 3 HTML report")
    p_report.add_argument("--results-dir", action="append", default=[], help="Allure results directory (repeatable)")
    p_report.add_argument("--output", "-o", default="allure-report", help="Output directory")
    p_report.add_argument("--config", "-c", default="allurerc.json", help="Allure 3 config file")
    p_report.add_argument("--open", action="store_true", dest="open_report", help="Open report in browser")
    p_report.set_defaults(func=_cmd_report)

    p_ci = subparsers.add_parser("ci-report", help="Run the full CI Allure 3 merge, summary, HTML, and Pages pipeline")
    p_ci.set_defaults(func=_cmd_ci_report)

    p_e2e = subparsers.add_parser("android-e2e", help="Configure ADB tunnels and run connectedDebugAndroidTest")
    p_e2e.add_argument("gradle_args", nargs=argparse.REMAINDER, help="Additional Gradle arguments")
    p_e2e.set_defaults(func=_cmd_android_e2e)

    return parser


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)
    return int(args.func(args))
