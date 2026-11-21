"""Turns build logs and test results into GitHub annotations, so failures show on the run's page
(and through the public API) without opening the full log.

Usage: annotate.py build LOG
       annotate.py core|youtube LOG RESULTS_DIR
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd()).rstrip("/") + "/"


def esc(s, prop=False):
    s = s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return s.replace(":", "%3A").replace(",", "%2C") if prop else s


def note(level, title, msg, file=None, line=None, col=None):
    props = [f"title={esc(title, True)}"]
    if file:
        props.append(f"file={esc(file, True)}")
    if line:
        props.append(f"line={line}")
    if col:
        props.append(f"col={col}")
    print(f"::{level} {','.join(props)}::{esc(msg[:4000])}")


def summary(text):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")


def from_log(log, level):
    """Kotlin compile errors and Gradle's 'What went wrong'. Returns how many were reported."""
    try:
        text = open(log, encoding="utf-8", errors="replace").read()
    except OSError:
        return 0
    n = 0
    for m in re.finditer(r"^e: (?:file://)?(\S+?):(\d+):(\d+) (.+)$", text, re.M):
        path = m.group(1)
        note(level, "Kotlin compile error", m.group(4),
             path[len(ROOT):] if path.startswith(ROOT) else path, m.group(2), m.group(3))
        n += 1
    m = re.search(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\Z)", text, re.S)
    if m and n == 0:
        note(level, "Gradle failed", m.group(1).strip())
        n += 1
    if n == 0 and "BUILD FAILED" in text:
        note(level, "Gradle failed", "\n".join(text.strip().splitlines()[-40:]))
        n += 1
    return n


def from_results(folder, level):
    passed, failed, skipped = [], [], []
    for path in glob.glob(os.path.join(folder, "*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for tc in root.iter("testcase"):
            name = f"{tc.get('classname', '').split('.')[-1]}.{tc.get('name')}"
            bad = tc.find("failure")
            if bad is None:
                bad = tc.find("error")
            if bad is not None:
                text = (bad.text or bad.get("message") or "").strip()
                # the assertion message first, without the long stack trace
                text = re.split(r"\n\s+at (?:org\.junit|androidx\.test|java\.lang\.reflect)", text)[0]
                note(level, f"Test {name} failed", text)
                failed.append(name)
            elif tc.find("skipped") is not None:
                skipped.append(name)
            else:
                passed.append(name)
    return passed, failed, skipped


def main():
    mode, log = sys.argv[1], sys.argv[2]
    if mode == "build":
        from_log(log, "error")
        return
    level = "warning" if mode == "youtube" else "error"
    passed, failed, skipped = from_results(sys.argv[3], level)
    if not passed and not failed:
        # nothing ran: the build or the install failed
        if from_log(log, level) == 0:
            note(level, f"E2E ({mode}): no test results", "No test results were produced. See the e2e-results artifact.")
    line = f"E2E {mode}: {len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped"
    print(line)
    note("notice", f"E2E {mode}", line + ("\nFailed: " + ", ".join(failed) if failed else ""))
    summary(f"### {line}\n" + "".join(f"- ✅ {t}\n" for t in passed) + "".join(f"- ❌ {t}\n" for t in failed))


main()
