#!/usr/bin/env python3
"""Serial-bound REA acceptance and computer backup. Python standard library only."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.aistudio.videoknowledgesorter.fkvbwy.preview"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MODEL = "aiconjured/Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-Q5_NVFP4:latest"
PERMISSIONS = ["android.permission." + x for x in (
    "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED")]


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_digest():
    paths = [p for p in (ROOT / "app/src").rglob("*") if p.is_file()]
    paths += [ROOT / p for p in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                                "gradle/libs.versions.toml", "gradle.properties")]
    result = hashlib.sha256()
    for path in sorted(paths):
        result.update(str(path.relative_to(ROOT)).encode() + b"\0" + digest(path).encode() + b"\n")
    return result.hexdigest()


def installed_base_path(output):
    paths = re.findall(r"^package:(/data/app/[^\r\n]+)$", output, re.M)
    if len(paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", paths[0]):
        raise RuntimeError("Expected one installed preview base APK")
    return paths[0]


def classify_runtime(serial, requested, props, waydroid_ip=None):
    """Two-sided evidence, not a caller-supplied runtime label alone."""
    if not serial or serial.startswith("-") or not re.fullmatch(r"[A-Za-z0-9_.:\[\]-]+", serial):
        raise ValueError("Invalid explicit ADB serial")
    qemu = any(props.get(k) == "1" for k in ("ro.kernel.qemu", "ro.boot.qemu"))
    if requested == "emulator":
        if not (re.fullmatch(r"emulator-\d+", serial) and qemu and
                props.get("ro.hardware") in ("ranchu", "goldfish")):
            raise ValueError("Refusing target without matching emulator serial and QEMU evidence")
    elif requested == "waydroid":
        identity = " ".join(props.get(k, "") for k in (
            "ro.hardware", "ro.product.device", "ro.product.system.device"))
        if not (waydroid_ip and serial == waydroid_ip + ":5555" and
                "waydroid" in identity.lower() and not qemu):
            raise ValueError("Refusing target without matching live host Waydroid IP and build evidence")
    else:
        raise ValueError("Choose emulator or waydroid explicitly; phones are not authorized")
    api = int(props.get("ro.build.version.sdk", "0"))
    return {"serial": serial, "runtime": requested, "api": api, "properties": props}


class Mission:
    def __init__(self, args):
        self.args = args
        self.adb = shutil.which("adb")
        self.rea = shutil.which("rea")
        if not self.adb or not self.rea:
            raise RuntimeError("Existing adb and rea executables are required")
        self.output = Path(args.output).resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.report = {"schema": 1, "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                       "status": "running", "phone_deployment": "not performed; requires user acceptance",
                       "steps": [], "limits": [
                           "One API level does not prove every Android release or OEM.",
                           "Local-model fixtures do not establish cloud-provider quality.",
                           "Waydroid host upgrades and image replacements are not performed."]}
        self.save()

    def save(self):
        tmp = self.output / "report.json.tmp"
        tmp.write_text(json.dumps(self.report, indent=2) + "\n")
        tmp.replace(self.output / "report.json")

    def run(self, argv, name, timeout=180, binary=False, required=True):
        started = time.monotonic()
        env = os.environ.copy()
        sdk = Path(self.adb).resolve().parent.parent
        if (sdk / "platforms").exists():
            env.setdefault("ANDROID_HOME", str(sdk))
        result = subprocess.run([str(x) for x in argv], cwd=ROOT, env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        (self.output / (name + (".bin" if binary else ".log"))).write_bytes(result.stdout)
        if result.stderr:
            (self.output / (name + ".stderr.log")).write_bytes(result.stderr)
        entry = {"name": name, "exit_code": result.returncode,
                 "seconds": round(time.monotonic() - started, 3)}
        self.report["steps"].append(entry)
        self.save()
        if required and result.returncode:
            raise RuntimeError(f"{name} failed ({result.returncode}); inspect {self.output}")
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def adb_call(self, *argv, name, **kwargs):
        return self.run([self.adb, "-s", self.args.serial, *argv], name, **kwargs)

    def rea_call(self, command, *argv, name):
        raw = self.run([self.rea, command, *argv, "--format", "json"], name)
        data = json.loads(raw)
        (self.output / (name + ".json")).write_text(json.dumps(data, indent=2) + "\n")
        if data.get("error") or data.get("isError") or data.get("confidence") == "unknown":
            raise RuntimeError(f"REA {command} did not produce successful evidence")
        return data

    def check(self):
        evidence = self.rea_call("inspect-adb-device", self.args.serial, name="rea-device")
        self.report["rea_device_evidence"] = evidence.get("evidence_id")
        raw = self.adb_call("shell", "getprop", name="properties")
        props = dict(re.findall(r"^\[([^]]+)\]: \[(.*)\]$", raw, re.M))
        ip = None
        if self.args.runtime == "waydroid":
            status = self.run(["waydroid", "status"], "waydroid-host")
            match = re.search(r"IP address:\s*(\S+)", status)
            if "RUNNING" in status and match:
                ip = match.group(1)
        self.report["target"] = classify_runtime(self.args.serial, self.args.runtime, props, ip)
        self.save()
        if self.report["target"]["api"] < 34:
            raise RuntimeError("Full acceptance requires Android14+/API34+; keep REA basic work separate")

    def instrument(self, name, classes, extra=(), minimum=1):
        output = self.adb_call("shell", "am", "instrument", "-w", "-r",
            "-e", "rea_runtime", self.args.runtime, "-e", "class", classes,
            "-e", "rea_video_fixture", self.report.get("video_fixture", "codex_emulator_workflow.mp4"),
            *extra, RUNNER, name=name, timeout=720)
        match = re.search(r"OK \((\d+) tests?\)", output)
        if not match or int(match.group(1)) < minimum or re.search(
                r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_CODE: 0|INSTRUMENTATION_STATUS_CODE: -[34]", output):
            raise RuntimeError(f"{name} did not pass its required test count; inspect retained log")
        self.report.setdefault("device_tests", {})[name] = int(match.group(1))
        self.save()

    def reset_media_grants(self, name):
        for index, permission in enumerate(PERMISSIONS):
            self.adb_call("shell", "pm", "revoke", PACKAGE, permission,
                          name=f"{name}-revoke-{index}")
            self.adb_call("shell", "pm", "clear-permission-flags", PACKAGE, permission,
                          "user-set", "user-fixed", name=f"{name}-flags-{index}")

    def verify_installed_hash(self):
        output = self.adb_call("shell", "pm", "path", PACKAGE, name="installed-apk-path")
        path = installed_base_path(output)
        output = self.adb_call("shell", "sha256sum", path, name="installed-apk-sha256")
        installed = output.split()[0]
        if installed != self.report["apk_sha256"] or digest(APK) != installed:
            raise RuntimeError("Installed, tested and current APK digests differ")
        self.report["installed_apk_sha256"] = installed
        self.save()

    def verify(self):
        self.check()
        self.verify_local_model()
        self.report["source_sha256"] = source_digest()
        self.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"],
                 "workflow-tests")
        self.run(["bash", "gradlew", "--offline", "--no-daemon", ":app:testDebugUnitTest",
                  ":app:lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                 "gradle", timeout=900)
        suites = [ET.parse(p).getroot() for p in (ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
        tests = sum(int(s.get("tests", "0")) for s in suites)
        failures = sum(int(s.get(k, "0")) for s in suites for k in ("failures", "errors", "skipped"))
        if tests < 55 or failures:
            raise RuntimeError("Unit-test count or failure/skipped count did not meet baseline")
        lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
        errors = [x for x in lint.findall("issue") if x.get("severity") in ("Error", "Fatal")]
        if errors:
            raise RuntimeError("Lint has errors")
        self.report.update(apk_sha256=digest(APK), test_apk_sha256=digest(TEST_APK),
            unit_tests=tests, lint_errors=0,
            lint_warnings=sum(x.get("severity") == "Warning" for x in lint.findall("issue")))
        self.save()
        self.rea_call("install-adb-package", self.args.serial, str(APK), "--replace", name="rea-install-app")
        self.rea_call("install-adb-package", self.args.serial, str(TEST_APK), "--replace", name="rea-install-tests")
        self.verify_installed_hash()
        if source_digest() != self.report["source_sha256"]:
            raise RuntimeError("App or test source changed during acceptance; freeze and rerun")
        self.provision_fixture()
        for phase in ("denied", "limited", "limited_video", "full", "revoked"):
            self.reset_media_grants(phase)
            self.instrument("permission-" + phase, "com.example.SelectedMediaPermissionTest",
                            ("-e", "rea_permission_phase", phase))
            self.adb_call("pull", f"/sdcard/Android/data/{PACKAGE}/files/rea-permission-{phase}.txt",
                          str(self.output / f"permission-{phase}.txt"), name="pull-permission-" + phase)
        for i, permission in enumerate(PERMISSIONS[:2]):
            self.adb_call("shell", "pm", "grant", PACKAGE, permission, name=f"grant-regression-{i}")
        self.instrument("regression", ",".join("com.example." + c for c in (
            "EmulatorWorkflowTest", "VideoProcessorCancellationTest", "ExampleInstrumentedTest",
            "UxEvidenceAccessibilityTest")), minimum=14)
        endpoint = "http://10.0.2.2:11434/api/generate"
        created_reverse = False
        if self.args.runtime == "waydroid":
            endpoint = "http://127.0.0.1:11434/api/generate"
            reverse = self.adb_call("reverse", "--list", name="reverse-before")
            existing_reverse = any(len(x.split()) >= 3 and x.split()[1:3] == ["tcp:11434", "tcp:11434"]
                                   for x in reverse.splitlines())
            conflicts = [x for x in reverse.splitlines() if "tcp:11434" in x and not existing_reverse]
            if conflicts:
                raise RuntimeError("Existing reverse mapping conflicts with local-model port")
            if not existing_reverse:
                self.adb_call("reverse", "tcp:11434", "tcp:11434", name="reverse-local-model")
                created_reverse = True
        try:
            self.instrument("live-local-inference", "com.example.LiveLocalInferenceTest", (
                "-e", "rea_live_ollama", "true", "-e", "rea_ollama_model", self.args.model,
                "-e", "rea_ollama_endpoint", endpoint, "-e", "rea_keep_review_video", "true"), minimum=2)
        except Exception:
            if created_reverse:
                self.adb_call("reverse", "--remove", "tcp:11434", name="remove-created-reverse")
            raise
        self.report["review_profile"] = {"endpoint": endpoint, "model": self.args.model,
            "synthetic_video_retained": True, "waydroid_reverse_retained": created_reverse}
        self.adb_call("logcat", "-d", "-v", "brief", "LiveLocalInference:I", "*:S", name="local-model-results")
        for theme in ("light", "dark"):
            self.adb_call("pull", f"/sdcard/Android/data/{PACKAGE}/files/rea-theme-{theme}.png",
                          str(self.output / f"theme-{theme}.png"), name="pull-theme-" + theme)
        self.backup()
        self.verify_installed_hash()
        if source_digest() != self.report["source_sha256"]:
            raise RuntimeError("App or test source changed during acceptance; freeze and rerun")
        self.rea_call("start-adb-app", self.args.serial, PACKAGE, name="rea-final-launch")
        self.rea_call("capture-adb-screen", self.args.serial, "--output-directory", str(self.output / "final-screen"),
                      name="rea-final-screen")
        self.report["status"] = "automated_checks_passed_pending_visual_and_user_review"
        self.report["visual_review"] = "Inspect theme-light.png, theme-dark.png and final-screen/screen.png"
        self.save()

    def provision_fixture(self):
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise RuntimeError("Existing free ffmpeg is required to generate the disposable video fixture")
        name = "rea_workflow_" + dt.datetime.now().strftime("%Y%m%d_%H%M%S") + ".mp4"
        path = self.output / name
        self.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "color=c=white:s=1280x720:r=1:d=4",
                  "-vf", "drawtext=text='ANDROID WORKFLOW TEST':fontcolor=black:fontsize=72:x=80:y=300",
                  "-c:v", "libx264", "-pix_fmt", "yuv420p", str(path)], "generate-video", timeout=60)
        remote = "/sdcard/Movies/" + name
        self.rea_call("push-adb-file", self.args.serial, str(path), remote, name="rea-provision-video")
        self.adb_call("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                      "-d", "file://" + remote, name="scan-fixture")
        self.report["video_fixture"] = name
        self.report["video_fixture_sha256"] = digest(path)
        self.save()

    def verify_local_model(self):
        with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=10) as response:
            tags = json.load(response)
        model = next((m for m in tags.get("models", []) if m.get("name") == self.args.model), None)
        if not model or model.get("remote_host") or model.get("remote_model") or "cloud" in self.args.model.lower():
            raise RuntimeError("An installed local model is required; no cloud aliases or downloads")
        self.report["local_model"] = {k: model.get(k) for k in ("name", "digest", "size")}
        self.save()

    def backup(self):
        from media_backup import backup
        self.rea_call("stop-adb-app", self.args.serial, PACKAGE, name="rea-quiesce-backup")
        try:
            result = backup(self.adb, self.args.serial, PACKAGE, self.output / "computer-backup")
            self.report["computer_backup"] = result
            self.save()
        finally:
            self.rea_call("start-adb-app", self.args.serial, PACKAGE, name="rea-resume-after-backup")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "verify", "backup"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--runtime", required=True, choices=("emulator", "waydroid"))
    parser.add_argument("--model", default=MODEL)
    parser.add_argument("--output", default=str(ROOT / "uat" / ("mission-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))))
    args = parser.parse_args()
    mission = None
    try:
        mission = Mission(args)
        if args.action == "verify":
            mission.verify()
        else:
            mission.check()
            if args.action == "backup":
                mission.backup()
            mission.report["status"] = "passed"
            mission.save()
        print(json.dumps({"status": mission.report["status"], "report": str(mission.output / "report.json")}))
    except Exception as error:
        if mission:
            mission.report.update(status="failed", error=str(error))
            mission.save()
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
