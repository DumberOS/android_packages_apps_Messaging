"""Packaging regressions: no native tools are needed to consume a prebuilt bundle."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('prebuilts', APP / 'tools/verify-dumber-chat-prebuilts.py')
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class PrebuiltTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dumber prebuilts ')
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name)
        self.bundle = self.app / 'prebuilts/dumber-chat'
        for abi, bits, machine in [('arm64-v8a', 2, 183), ('armeabi-v7a', 1, 40)]:
            header = bytearray(64)
            header[:6] = b'\x7fELF' + bytes([bits, 1])
            struct.pack_into('<HH', header, 16, 3, machine)
            self.write(abi + '/libdumber_chat_core.so', header)
        for name in ['ChatClient.kt', 'Models.kt']:
            self.write('kotlin/src/main/kotlin/chat/dumber/sdk/' + name, b'fixture')
        self.write('THIRD_PARTY_NOTICES.md', b'fixture notices')
        self.hashes = {str(p.relative_to(self.bundle)): hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in self.bundle.rglob('*') if p.is_file()}
        self.manifest()

    def write(self, name, content):
        p = self.bundle / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)

    def manifest(self):
        (self.bundle / 'manifest.json').write_text(json.dumps({'sha256': self.hashes}))

    def test_complete_bundle_is_accepted(self):
        VERIFIER.verify(self.bundle)

    def test_changed_binding_is_rejected(self):
        self.write('kotlin/src/main/kotlin/chat/dumber/sdk/Models.kt', b'incompatible binding')
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            VERIFIER.verify(self.bundle)

    def test_wrong_abi_is_rejected_even_with_matching_checksum(self):
        name = 'arm64-v8a/libdumber_chat_core.so'
        self.write(name, (self.bundle / 'armeabi-v7a/libdumber_chat_core.so').read_bytes())
        self.hashes[name] = hashlib.sha256((self.bundle / name).read_bytes()).hexdigest()
        self.manifest()
        with self.assertRaisesRegex(ValueError, 'architecture'):
            VERIFIER.verify(self.bundle)

    def test_incomplete_manifest_is_rejected(self):
        self.hashes.pop('arm64-v8a/libdumber_chat_core.so')
        self.manifest()
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            VERIFIER.verify(self.bundle)

    def test_missing_library_is_rejected(self):
        (self.bundle / 'armeabi-v7a/libdumber_chat_core.so').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing'):
            VERIFIER.verify(self.bundle)

    def test_prepare_works_without_cargo_or_ndk(self):
        (self.app / 'tools').mkdir()
        for name in ['prepare-dumber-chat.sh', 'verify-dumber-chat-prebuilts.py']:
            shutil.copyfile(APP / 'tools' / name, self.app / 'tools' / name)
        (self.app / 'bin').mkdir()
        for name in ['cargo', 'rustc', 'rustup', 'sdkmanager']:
            p = self.app / 'bin' / name
            p.write_text('#!/bin/sh\ntouch "$FORBIDDEN_TOOL_MARKER"\nexit 91\n')
            p.chmod(0o755)
        marker = self.app / 'forbidden-tool'
        env = dict(os.environ, PATH=str(self.app / 'bin') + ':' + os.environ['PATH'],
                   ANDROID_NDK_HOME='/nonexistent-ndk', CARGO_HOME='/nonexistent-cargo',
                   FORBIDDEN_TOOL_MARKER=str(marker))
        result = subprocess.run(['bash', str(self.app / 'tools/prepare-dumber-chat.sh')],
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
