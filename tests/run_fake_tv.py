#!/usr/bin/env python3
"""Run a temporary local fake TV; Android emulators connect to 10.0.2.2.

The fake TV binds only 127.0.0.1. Its certificate/paired-client list are ephemeral.
It never contacts a real TV. Ctrl-C stops it and removes its temporary test key.
"""
import argparse
from pathlib import Path
import signal
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pairing-port", type=int, default=6467)
    parser.add_argument("--remote-port", type=int, default=6466)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    java = root / "app/src/main/java/uy/nico/bareremote"
    with tempfile.TemporaryDirectory(prefix="bareremote-fake-tv-") as directory:
        temporary = Path(directory)
        store = temporary / "fake-tv.p12"
        subprocess.run([
            "keytool", "-genkeypair", "-alias", "fake-tv", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=BareRemote Fake TV", "-validity", "1", "-storetype", "PKCS12",
            "-keystore", str(store), "-storepass", "fake-tv-test", "-keypass", "fake-tv-test",
        ], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        subprocess.run([
            "javac", "--release", "8", "-d", str(temporary), str(java / "Proto.java"),
            str(java / "TvProtocol.java"), str(java / "PictureStateWire.java"),
                str(java / "PictureStateClient.java"), str(root / "tests/FakeTv.java"),
        ], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        process = subprocess.Popen([
            "java", "-cp", str(temporary), "uy.nico.bareremote.FakeTv", str(store),
            str(args.pairing_port), str(args.remote_port),
        ])
        def terminate(signum, frame):
            process.terminate()
        signal.signal(signal.SIGTERM, terminate)
        try:
            process.wait()
        except KeyboardInterrupt:
            process.terminate()
        finally:
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main()
