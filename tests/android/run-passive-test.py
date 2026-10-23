#!/usr/bin/env python3
"""Verify native reopen/Settings/Sync are passive against a loopback-only TV.

Build the app and tests/android/build-keyboard-tests.sh first. This intentionally
clears only the selected emulator's disposable BareRemote test preferences.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emulator", required=True)
    parser.add_argument("--adb", default=str(root.parent / ".tools/platform-tools/adb"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.emulator):
        parser.error("An explicitly selected emulator serial is required")
    adb = [args.adb, "-s", args.emulator]
    def command(*values, timeout=20):
        return subprocess.run(adb + list(values), capture_output=True, text=True, check=True, timeout=timeout)
    if command("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise RuntimeError("Refusing to use a physical device")
    for apk in (root / "build/BareRemote.apk", root / "build/android-keyboard-tests/KeyboardTests.apk"):
        command("install", "--no-incremental", "-r", str(apk), timeout=45)
    command("shell", "pm", "grant", "uy.nico.bareremote", "android.permission.POST_NOTIFICATIONS")
    command("shell", "am", "force-stop", "uy.nico.bareremote")
    ready = threading.Event()
    explicit_key = threading.Event()
    lines, errors = [], []
    with tempfile.TemporaryDirectory(prefix="bareremote-passive-") as temporary:
        code_file = Path(temporary) / "pair-code"
        environment = os.environ.copy()
        environment.pop("BAREREMOTE_ADB", None)
        environment.pop("BAREREMOTE_EMULATOR_SERIAL", None)
        fake = subprocess.Popen([sys.executable, str(root / "tests/run_fake_tv.py")],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=environment)
        def observe():
            try:
                for line in fake.stdout:
                    lines.append(line)
                    if "FAKE_TV_READY" in line:
                        ready.set()
                    if "KEY_APPLIED" in line:
                        explicit_key.set()
                        ack = Path(temporary) / "key-applied"
                        ack.write_text("applied", encoding="ascii")
                        target = "/sdcard/Android/data/uy.nico.bareremote/files/passive-test-key-applied"
                        command("push", str(ack), target + ".incoming")
                        command("shell", "mv", target + ".incoming", target)
                    match = re.search(r"PAIR_CODE id=[0-9]+ code=([0-9A-F]{6})", line)
                    if match:
                        code_file.write_text(match.group(1), encoding="ascii")
                        code_file.chmod(0o600)
                        remote_code = "/sdcard/Android/data/uy.nico.bareremote/files/passive-test-code"
                        command("push", str(code_file), remote_code + ".incoming")
                        command("shell", "mv", remote_code + ".incoming", remote_code)
                        code_file.unlink()
            except BaseException as failure:
                errors.append(type(failure).__name__)
        reader = threading.Thread(target=observe, daemon=True)
        reader.start()
        try:
            if not ready.wait(15):
                raise RuntimeError("Loopback TV did not start")
            result = command("shell", "am", "instrument", "-w", "-r",
                             "uy.nico.bareremote.keyboardtest/uy.nico.bareremote.PassiveOpenInstrumentation", timeout=110)
            print(result.stdout, end="")
            if "INSTRUMENTATION_CODE: -1" not in result.stdout or "FAIL:" in result.stdout:
                raise AssertionError("Native passive-open instrumentation failed")
            if errors:
                raise AssertionError("Loopback harness failed: " + ", ".join(errors))
            if not explicit_key.wait(3):
                raise AssertionError("The fake TV did not confirm the explicit key")
            launches = sum("PICTURE_REQUEST" in line or "APP_LINK_REQUEST" in line for line in lines)
            sessions = sum("REMOTE_READY" in line for line in lines)
            keys = sum("KEY_APPLIED" in line for line in lines)
            if launches != 0 or sessions < 5 or keys != 1:
                raise AssertionError(f"Expected zero TV launches and one explicit key; got launches={launches}, sessions={sessions}, keys={keys}")
            print(f"Native passive lifecycle: {sessions} pinned sessions, zero TV app links, one explicit key delivered.")
        finally:
            command("shell", "am", "force-stop", "uy.nico.bareremote")
            fake.terminate()
            try:
                fake.wait(timeout=5)
            except subprocess.TimeoutExpired:
                fake.kill(); fake.wait()
            reader.join(timeout=2)
            # The pairing code and any regression-request tokens stay in a private ignored log.
            diagnostic = root / "build/passive-native-fake.log"
            diagnostic.write_text("".join(lines))
            diagnostic.chmod(0o600)


if __name__ == "__main__":
    main()
