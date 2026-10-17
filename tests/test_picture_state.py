"""Independent picture callback crypto vectors and real one-shot loopback UDP tests."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class PictureStateTests(unittest.TestCase):
    def test_authenticated_bounded_callbacks(self):
        root = Path(__file__).resolve().parents[1]
        java = root / "app/src/main/java/uy/nico/bareremote"
        with tempfile.TemporaryDirectory(prefix="bareremote-picture-") as temporary:
            subprocess.run([
                "javac", "--release", "8", "-d", temporary,
                str(java / "PictureStateWire.java"), str(java / "PictureStateClient.java"),
                str(root / "tests/PictureStateTest.java"),
            ], check=True, capture_output=True, text=True)
            result = subprocess.run([
                "java", "-cp", temporary, "uy.nico.bareremote.PictureStateTest",
            ], check=False, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
