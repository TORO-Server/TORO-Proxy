#!/usr/bin/env python3
"""ops/both-service/fetch_plugins.py の試験 (CI と手元。標準ライブラリだけ、通信なし)。"""
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True  # ops/ に __pycache__ を残さない
SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "ops/both-service/fetch_plugins.py"
spec = importlib.util.spec_from_file_location("fetch_plugins", SCRIPT)
fetch_plugins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch_plugins)

BODY = b"jar body"
GOOD = hashlib.sha256(BODY).hexdigest()
URL = "https://example.invalid/plugin.jar"


def fake_urlopen(*_args, **_kwargs):
    return io.BytesIO(BODY)


class FetchTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dest = pathlib.Path(tmp.name, "dest")
        self.current = pathlib.Path(tmp.name, "current")
        self.dest.mkdir()
        self.current.mkdir()
        self.outside = pathlib.Path(tmp.name, "outside.jar")

    def run_fetch(self, name="p.jar", pin=None, opener=fake_urlopen):
        pin = {"version": "1.0", "url": URL, "sha256": GOOD} if pin is None else pin
        with mock.patch.object(fetch_plugins.urllib.request, "urlopen", opener):
            return fetch_plugins.fetch(name, pin, str(self.dest), str(self.current))

    def names(self):
        return sorted(p.name for p in self.dest.iterdir())

    def test_downloads_when_hash_matches(self):
        self.assertTrue(self.run_fetch())
        self.assertEqual(self.names(), ["p.jar"])
        self.assertEqual((self.dest / "p.jar").read_bytes(), BODY)

    def test_skips_when_current_matches(self):
        (self.current / "p.jar").write_bytes(BODY)
        opener = mock.Mock(side_effect=AssertionError("取りに行ってはいけない"))
        self.assertTrue(self.run_fetch(opener=opener))
        self.assertEqual(self.names(), [])

    def test_rejects_wrong_hash(self):
        pin = {"version": "1.0", "url": URL, "sha256": "0" * 64}
        self.assertFalse(self.run_fetch(pin=pin))
        self.assertEqual(self.names(), [])

    def test_rejects_oversized_download(self):
        with mock.patch.object(fetch_plugins, "MAX_BYTES", 1):
            self.assertFalse(self.run_fetch())
        self.assertEqual(self.names(), [])

    def test_does_not_trust_symlink_in_plugins(self):
        self.outside.write_bytes(BODY)
        try:
            os.symlink(self.outside, self.current / "p.jar")
        except OSError:
            self.skipTest("この環境ではシンボリックリンクを作れない")
        self.assertTrue(self.run_fetch())
        self.assertEqual(self.names(), ["p.jar"])

    def test_rejects_bad_lock_entries(self):
        for name, pin in [
            ("../p.jar", {"url": URL, "sha256": GOOD}),
            ("p.jar", {"url": "http://example.invalid/p.jar", "sha256": GOOD}),
            ("p.jar", {"url": URL, "sha256": "abc"}),
            ("p.jar", "not a table"),
        ]:
            self.assertFalse(self.run_fetch(name=name, pin=pin), name)
        self.assertEqual(self.names(), [])

    def test_lock_file_is_well_formed(self):
        lock_path = SCRIPT.with_name("plugins.lock.json")
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        self.assertTrue(lock)
        for name, pin in lock.items():
            self.assertRegex(name, fetch_plugins.NAME)
            self.assertRegex(pin["sha256"], fetch_plugins.SHA256)
            self.assertTrue(pin["url"].startswith("https://"))
            self.assertRegex(pin["version"], r"^[0-9]+(\.[0-9]+)+$")


if __name__ == "__main__":
    unittest.main()
