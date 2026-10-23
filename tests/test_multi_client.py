"""Exercise subject replacement, independent phone keys, and simultaneous remote TLS."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class MultiClientTests(unittest.TestCase):
    def test_pairing_trust_and_simultaneous_remotes(self):
        root = Path(__file__).resolve().parents[1]
        java = root / "app/src/main/java/uy/nico/bareremote"
        subjects = (
            "CN=BareRemote test TV",
            "CN=BareRemote",
            "CN=BareRemote",
            "CN=BareRemote/00000000-0000-4000-8000-000000000001",
            "CN=BareRemote/00000000-0000-4000-8000-000000000002",
        )
        with tempfile.TemporaryDirectory(prefix="bareremote-multi-client-") as directory:
            temporary = Path(directory)
            stores = []
            for index, subject in enumerate(subjects):
                store = temporary / f"identity-{index}.p12"
                subprocess.run([
                    "keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", subject, "-validity", "1", "-storetype", "PKCS12",
                    "-keystore", str(store), "-storepass", "test-only", "-keypass", "test-only",
                ], check=True, capture_output=True, text=True, timeout=15)
                stores.append(str(store))
            subprocess.run([
                "javac", "--release", "8", "-d", str(temporary),
                *[str(java / name) for name in (
                    "Proto.java", "TvProtocol.java", "KeyboardState.java", "KeyboardSession.java", "PictureStateWire.java", "PictureStateClient.java")],
                str(root / "tests/FakeTv.java"), str(root / "tests/MultiClientTest.java"),
            ], check=True, capture_output=True, text=True, timeout=15)
            result = subprocess.run([
                "java", "-cp", str(temporary), "uy.nico.bareremote.MultiClientTest", *stores,
            ], check=False, capture_output=True, text=True, timeout=35)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
