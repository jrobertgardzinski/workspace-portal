#!/usr/bin/env python3
"""Per-service Documentation.md, generated from each microservice's own Allure results.

Modeled on microservice-security's Documentation.md (shared workspace): the README stays a
hand-written brochure pointing at the executable specs, and the detailed reference is a
GENERATED document — the Allure report of the unit/integration suite, rendered as markdown.
Where security's file was a one-off copy of the estate-wide report, this script scopes the
report per service: every `microservice-*` directory that has `**/target/allure-results`
gets its own `Documentation.md`, listing only its own modules and behaviors.

Run `./mvnw clean test` (and, for microservice-image, `pytest --alluredir=target/allure-results`)
first — the script only reads existing results. Stale reruns are defused by deduplicating on
Allure's historyId (same test + same parameters), keeping the latest execution.

Output files are versioned on purpose: a PR that changes behavior shows its documentation
changing in the same diff.
"""

import glob
import json
import os
import re
from collections import defaultdict
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
SKIP_DIRS = {"node_modules", ".git", "src", "dist", "__pycache__", "pacts", "specs", "docs"}
SCENARIO_RE = re.compile(r"^\s*(?:Rule|Scenario Outline|Scenario|Example)\s*:\s*(.*)")


def format_duration(seconds):
    if seconds < 0.001:
        return "< 1ms"
    if seconds < 1:
        return f"{seconds*1000:.0f}ms"
    return f"{seconds:.2f}s"


def get_label(data, label_name):
    for label in data.get("labels", []):
        if label.get("name") == label_name:
            return label.get("value")
    return None


def find_results_dirs(service_dir):
    for dirpath, dirs, _files in os.walk(service_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        if "allure-results" in dirs:
            yield os.path.join(dirpath, "allure-results")


def load_results(results_dir):
    """Latest execution per test: reruns without `clean` append, so dedup on historyId."""
    latest = {}
    for result_file in glob.glob(os.path.join(results_dir, "*-result.json")):
        try:
            with open(result_file, encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            print(f"WARN: unreadable {result_file}: {e}")
            continue
        params = tuple(sorted((p.get("name", ""), p.get("value", ""))
                              for p in data.get("parameters", [])))
        key = data.get("historyId") or (data.get("fullName"), data.get("name"), params)
        if key not in latest or data.get("stop", 0) > latest[key].get("stop", 0):
            latest[key] = data
    return latest.values()


def feature_index(service_dir):
    """basename -> path for every spec file; scenario names get resolved from these."""
    index = {}
    for dirpath, dirs, files in os.walk(service_dir):
        dirs[:] = [d for d in dirs if d not in {"node_modules", ".git", "target", "dist"}]
        for f in files:
            if f.endswith(".feature"):
                index.setdefault(f, os.path.join(dirpath, f))
    return index


def scenario_name(full_name, features):
    """The Cucumber results here carry name:"" and fullName "<file>.feature:<line>" —
    recover the scenario's own title from the spec file, scanning upward from the given
    line. Anonymous "Example:" blocks are titled by their nearest "Rule:" above (the
    estate's spec convention), so an untitled match keeps climbing."""
    m = re.match(r"(?:.*[/\\])?([^/\\:]+\.feature):(\d+)$", full_name or "")
    if not m:
        return None
    path = features.get(m.group(1))
    if not path:
        return None
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return None
    for line in reversed(lines[:int(m.group(2))]):
        sm = SCENARIO_RE.match(line)
        if sm and sm.group(1).strip():
            return sm.group(1).strip()
    return None


def module_name(results_dir, service_dir):
    # <service>/<module>/target/allure-results -> <module>; single-module -> service name
    rel = os.path.relpath(os.path.dirname(os.path.dirname(results_dir)), service_dir)
    return os.path.basename(service_dir) if rel == "." else rel.replace(os.sep, "/")


def collect(service_dir):
    modules = []
    doc_tree = {}
    features = feature_index(service_dir)
    for results_dir in sorted(find_results_dirs(service_dir)):
        stats = defaultdict(int)
        issues = []
        name = module_name(results_dir, service_dir)
        for data in load_results(results_dir):
            test_name = (data.get("name")
                         or scenario_name(data.get("fullName"), features)
                         or data.get("fullName") or "Unknown Test")
            status = data.get("status", "unknown")
            stats[status] += 1
            stats["total"] += 1
            duration = (data.get("stop", 0) - data.get("start", 0)) / 1000.0
            stats["duration"] += duration
            if status != "passed":
                issues.append({
                    "name": test_name,
                    "status": status,
                    "message": data.get("statusDetails", {}).get("message", "No message"),
                    "fullName": data.get("fullName", ""),
                })
                continue
            epic = get_label(data, "epic")
            if not epic:
                # Cucumber scenarios carry the .feature file's name as their `feature`
                # label but no epic — group the executable specs under their own roof
                # instead of a meaningless "Other".
                framework = (get_label(data, "framework") or "").lower()
                is_gherkin = framework.startswith("cucumber") or \
                    data.get("fullName", "").endswith(".feature")
                epic = "Executable specs" if is_gherkin else "Other"
            feature = get_label(data, "feature") or "General"
            story = get_label(data, "story") or ""
            tests = (doc_tree.setdefault(epic, {})
                             .setdefault(feature, {})
                             .setdefault(story, []))
            params = [f"{p.get('name')}: `{p.get('value')}`"
                      for p in data.get("parameters", [])
                      if p.get("mode") != "hidden"]
            tests.append({"name": test_name,
                          "duration": duration, "params": params, "module": name})
        if stats["total"] > 0:
            modules.append({"module": name, "stats": stats, "issues": issues})
    return modules, doc_tree


def render(service, modules, doc_tree):
    md = f"# {service} — Test Report & Documentation\n\n"
    md += f"Generated from Allure results by `build_documentation.py` on {date.today()}. "
    md += "Behaviors below are **verified by passing tests** — rerun the suite, rerun this "
    md += "script, and the document cannot drift from the code.\n\n"

    md += "## \U0001F4CA Execution Summary\n\n"
    md += "| Module | Total | Passed | Failed | Broken | Skipped | Duration |\n"
    md += "| :--- | :---: | :---: | :---: | :---: | :---: | :---: |\n"
    grand = defaultdict(float)
    for res in modules:
        s = res["stats"]
        md += (f"| {res['module']} | {s['total']} | {s['passed']} | {s['failed']} "
               f"| {s['broken']} | {s['skipped']} | {format_duration(s['duration'])} |\n")
        for k, v in s.items():
            grand[k] += v
    if len(modules) > 1:
        md += (f"| **TOTAL** | **{int(grand['total'])}** | **{int(grand['passed'])}** "
               f"| **{int(grand['failed'])}** | **{int(grand['broken'])}** "
               f"| **{int(grand['skipped'])}** | **{format_duration(grand['duration'])}** |\n")
    md += "\n"

    md += "## \U0001F4DD Test Documentation (Behaviors)\n\n"
    md += "This section describes the verified system behaviors based on passing tests.\n\n"
    for epic in sorted(doc_tree):
        md += f"### Epic: {epic}\n\n"
        for feature in sorted(doc_tree[epic]):
            md += f"#### Feature: {feature}\n\n"
            for story, tests in sorted(doc_tree[epic][feature].items()):
                if story:
                    md += f"##### Story: {story}\n\n"
                for test in sorted(tests, key=lambda x: x["name"]):
                    md += f"- **{test['name']}**\n"
                    if test["params"]:
                        md += "  - *Parameters:*\n"
                        for param in test["params"]:
                            md += f"    - {param}\n"
                md += "\n"

    if any(res["issues"] for res in modules):
        md += "## ⚠️ Issues (Failed, Broken, or Skipped)\n\n"
        for res in modules:
            if not res["issues"]:
                continue
            md += f"### Module: {res['module']}\n\n"
            for test in res["issues"]:
                emoji = ("❌" if test["status"] == "failed"
                         else "\U0001F525" if test["status"] == "broken" else "⏭️")
                md += f"#### {emoji} {test['name']} ({test['status']})\n"
                if test["fullName"]:
                    md += f"**Full Name:** `{test['fullName']}`\n\n"
                md += f"**Message:**\n```\n{test['message']}\n```\n\n"
    return md


def main():
    services = sorted(d for d in os.listdir(HERE)
                      if d.startswith("microservice-") and os.path.isdir(os.path.join(HERE, d)))
    for service in services:
        service_dir = os.path.join(HERE, service)
        modules, doc_tree = collect(service_dir)
        if not modules:
            print(f"SKIP {service}: no allure-results (run the tests first)")
            continue
        dest = os.path.join(service_dir, "Documentation.md")
        with open(dest, "w", encoding="utf-8") as f:
            f.write(render(service, modules, doc_tree))
        total = sum(res["stats"]["total"] for res in modules)
        print(f"{service}: Documentation.md ({total} tests, {len(modules)} module(s))")


if __name__ == "__main__":
    main()
