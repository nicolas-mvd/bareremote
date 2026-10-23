#!/usr/bin/env python3
"""Exercise the native macOS Keychain/TLS client against the independent Java TV.

Run from the repository: python3 macos/Tests/test_loopback.py
Only 127.0.0.1 is used. A temporary server key and test-only Keychain identities
are created; successful tests delete the latter and the runner removes fixtures.
Requires macOS, Swift 5.9+, Python 3, and a JDK with keytool/javac/java.
"""
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading


def check_events(log):
    """Check dispatch, not just socket writes, with no duplicate/replayed keys."""
    ready = re.findall(r"^REMOTE_READY id=(\d+).*client=([0-9a-f]+) subject=([0-9a-f]+)", log, re.M)
    if len(ready) != 2:
        raise AssertionError(f"Expected exactly two authenticated remote sessions; got {len(ready)}")
    first, second = ready[0][0], ready[1][0]
    if first == second or ready[0][1:] != ready[1][1:]:
        raise AssertionError("Reconnect must reuse the original Keychain identity on a fresh session")
    expected = [(first, "19", "SHORT"), (first, "66", "START_LONG"),
                (first, "66", "END_LONG"), (second, "20", "SHORT")]
    received = re.findall(r"^KEY id=(\d+) code=(\d+) direction=(\w+)", log, re.M)
    applied = re.findall(r"^KEY_APPLIED id=(\d+) code=(\d+) direction=(\w+)", log, re.M)
    if received != expected or applied != expected:
        raise AssertionError(f"Commands must arrive and apply exactly once in order: received={received}, applied={applied}")
    if "KEY_IGNORED_NOT_READY" in log:
        raise AssertionError("A key raced simulated TV input registration")
    if len(re.findall(r"^PAIRED ", log, re.M)) != 1:
        raise AssertionError("Reconnect and pin rejection must not request another pairing")
    if not re.search(rf"^PING_OK id={first}$", log, re.M):
        raise AssertionError("The native reader did not answer the server's ping")
    links = re.findall(r"^APP_LINK_REQUEST id=(\d+) uri=(\S+)", log, re.M)
    if links != [(first, "barehome://inputs")]:
        raise AssertionError(f"Unexpected app-link delivery: {links}")
    starts = re.findall(r"^KEY id=\d+ code=66 direction=START_LONG at_ms=(\d+)", log, re.M)
    ends = re.findall(r"^KEY id=\d+ code=66 direction=END_LONG at_ms=(\d+)", log, re.M)
    if len(starts) != 1 or len(ends) != 1 or not 600 <= int(ends[0]) - int(starts[0]) <= 1_500:
        raise AssertionError("Long OK must deliver a bounded press and release")


def main():
    if sys.platform != "darwin":
        raise SystemExit("This suite requires macOS: it exercises Security.framework and the real Keychain.")
    for command in ("swift", "keytool", "javac", "java"):
        if shutil.which(command) is None:
            raise SystemExit(f"Required tool is unavailable: {command}")
    macos = Path(__file__).resolve().parents[1]
    root = macos.parent
    java = root / "app/src/main/java/uy/nico/bareremote"
    with tempfile.TemporaryDirectory(prefix="bareremote-native-loopback-") as directory:
        temporary = Path(directory)
        store = temporary / "fake-tv.p12"
        certificate = temporary / "fake-tv.der"
        code_file = temporary / "pairing-code"
        subprocess.run([
            "keytool", "-genkeypair", "-alias", "fake-tv", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=BareRemote native loopback test", "-validity", "1", "-storetype", "PKCS12",
            "-keystore", str(store), "-storepass", "fake-tv-test", "-keypass", "fake-tv-test",
        ], check=True, capture_output=True, text=True, timeout=30)
        subprocess.run([
            "keytool", "-exportcert", "-alias", "fake-tv", "-keystore", str(store),
            "-storepass", "fake-tv-test", "-file", str(certificate),
        ], check=True, capture_output=True, text=True, timeout=10)
        subprocess.run([
            "javac", "--release", "8", "-d", str(temporary), str(java / "Proto.java"),
            str(java / "TvProtocol.java"), str(java / "PictureStateWire.java"),
            str(java / "PictureStateClient.java"), str(java / "DogStateWire.java"), str(java / "DogStateClient.java"), str(java / "KeyboardState.java"),
            str(java / "KeyboardSession.java"), str(root / "tests/FakeTv.java"),
            str(macos / "Tests/NativeFaultTv.java"),
            str(macos / "Tests/NativeImeTv.java"),
        ], check=True, capture_output=True, text=True, timeout=30)
        # Keep all reservations open while choosing ports so they cannot coincide.
        with socket.socket() as pair_reservation, socket.socket() as remote_reservation, \
                socket.socket() as fault_reservation, socket.socket() as fault_remote_reservation, \
                socket.socket() as ime_reservation:
            pair_reservation.bind(("127.0.0.1", 0))
            remote_reservation.bind(("127.0.0.1", 0))
            fault_reservation.bind(("127.0.0.1", 0))
            fault_remote_reservation.bind(("127.0.0.1", 0))
            ime_reservation.bind(("127.0.0.1", 0))
            pairing_port = pair_reservation.getsockname()[1]
            remote_port = remote_reservation.getsockname()[1]
            fault_port = fault_reservation.getsockname()[1]
            fault_remote_port = fault_remote_reservation.getsockname()[1]
            ime_port = ime_reservation.getsockname()[1]
        environment = os.environ.copy()
        # This runner never creates emulator forwarding or contacts a LAN device.
        environment.pop("BAREREMOTE_ADB", None)
        environment.pop("BAREREMOTE_EMULATOR_SERIAL", None)
        process = subprocess.Popen([
            "java", "-cp", str(temporary), "uy.nico.bareremote.FakeTv", str(store),
            str(pairing_port), str(remote_port),
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=environment)
        fault_process = subprocess.Popen([
            "java", "-cp", str(temporary), "uy.nico.bareremote.NativeFaultTv", str(store),
            str(fault_port), str(fault_remote_port),
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=environment)
        ime_process = subprocess.Popen([
            "java", "-cp", str(temporary), "uy.nico.bareremote.NativeImeTv", str(store), str(ime_port),
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=environment)
        ready = threading.Event()
        faults_ready = threading.Event()
        ime_ready = threading.Event()
        lines = []
        fault_lines = []
        ime_lines = []

        def watch():
            for line in process.stdout:
                lines.append(line)
                if "FAKE_TV_READY" in line:
                    ready.set()
                code = re.search(r"^PAIR_CODE id=\d+ code=([0-9A-F]{6}) ", line)
                if code:
                    pending = temporary / "pairing-code.pending"
                    pending.write_text(code.group(1), encoding="ascii")
                    pending.replace(code_file)

        reader = threading.Thread(target=watch, daemon=True)
        reader.start()

        def watch_faults():
            for line in fault_process.stdout:
                fault_lines.append(line)
                if "NATIVE_FAULT_TV_READY" in line:
                    faults_ready.set()

        fault_reader = threading.Thread(target=watch_faults, daemon=True)
        fault_reader.start()

        def watch_ime():
            for line in ime_process.stdout:
                ime_lines.append(line)
                if "NATIVE_IME_TV_READY" in line:
                    ime_ready.set()

        ime_reader = threading.Thread(target=watch_ime, daemon=True)
        ime_reader.start()
        try:
            if not ready.wait(10) or not faults_ready.wait(10) or not ime_ready.wait(10):
                raise AssertionError("The loopback Java fixtures did not start")
            environment.update({
                "BAREREMOTE_TEST_LOOPBACK": "1",
                "BAREREMOTE_TEST_PAIRING_PORT": str(pairing_port),
                "BAREREMOTE_TEST_REMOTE_PORT": str(remote_port),
                "BAREREMOTE_TEST_FAULT_PORT": str(fault_port),
                "BAREREMOTE_TEST_FAULT_REMOTE_PORT": str(fault_remote_port),
                "BAREREMOTE_TEST_IME_PORT": str(ime_port),
                "BAREREMOTE_TEST_CODE_FILE": str(code_file),
                "BAREREMOTE_TEST_CERTIFICATE_FILE": str(certificate),
            })
            result = subprocess.run([
                "swift", "test", "--package-path", str(macos), "--filter", "(LoopbackIntegrationTests|TransportIntegrationTests)",
            ], env=environment, capture_output=True, text=True, timeout=150)
            print(result.stdout, end="")
            print(result.stderr, end="", file=sys.stderr)
            if result.returncode != 0:
                raise AssertionError(f"Native XCTest exited with status {result.returncode}")
            check_events("".join(lines))
            if any("IME_FAILED" in line for line in ime_lines) or \
                    sum("IME_DONE session=" in line for line in ime_lines) != 3:
                raise AssertionError("The native IME fixture did not confirm every expected single-delivery frame")
            print("Native loopback: Keychain mutual TLS, TV code, exact pin, reconnect, pin rejection, "
                  "ping, single-delivery keys, long OK, app link, frame faults, deadlines, close and native IME passed.")
        except BaseException:
            diagnostic = re.sub(r"code=[0-9A-F]{6}", "code=<local-code>", "".join(lines))
            print("Local fake-TV diagnostics:\n" + diagnostic + "".join(fault_lines) + "".join(ime_lines), file=sys.stderr)
            raise
        finally:
            for child in (process, fault_process, ime_process):
                child.terminate()
                try:
                    child.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            reader.join(timeout=1)
            fault_reader.join(timeout=1)
            ime_reader.join(timeout=1)


if __name__ == "__main__":
    main()
