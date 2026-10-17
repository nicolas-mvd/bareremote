"""Compile/run the dependency-free wire and loopback TLS tests using the local JDK."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class ProtocolTests(unittest.TestCase):
    def test_wire_and_mutual_tls(self):
        root = Path(__file__).resolve().parents[1]
        java = root / "app/src/main/java/uy/nico/bareremote"
        with tempfile.TemporaryDirectory(prefix="bareremote-protocol-") as temporary:
            tmp = Path(temporary)
            store = tmp / "test.p12"
            subprocess.run([
                "keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=BareRemote loopback test", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", str(store), "-storepass", "test-only", "-keypass", "test-only",
            ], check=True, capture_output=True, text=True)
            subprocess.run([
                "javac", "--release", "8", "-d", str(tmp),
                str(java / "Proto.java"), str(java / "TvProtocol.java"), str(java / "PictureStateWire.java"),
                str(java / "PictureStateClient.java"), str(root / "tests/ProtocolTest.java"),
                str(root / "tests/ProtocolCli.java"),
            ], check=True, capture_output=True, text=True)
            result = subprocess.run([
                "java", "-cp", str(tmp), "uy.nico.bareremote.ProtocolTest", str(store),
            ], check=False, capture_output=True, text=True, timeout=40)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
