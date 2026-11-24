"""unittest-discovery entry point for the full isolated Java/socket suite."""
from pathlib import Path
import subprocess
import sys
import unittest


class OfflineJavaServerTests(unittest.TestCase):
    def test_server_and_core(self):
        subprocess.run([sys.executable, str(Path(__file__).with_name('host_tests.py'))],
                       check=True, timeout=90)
