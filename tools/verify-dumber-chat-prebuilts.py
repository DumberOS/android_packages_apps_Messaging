#!/usr/bin/env python3
"""Validate the shipped JNI/binding bundle without a compiler or network access."""
import argparse
import hashlib
import json
import struct
from pathlib import Path


def verify(bundle):
    manifest = json.loads((bundle / 'manifest.json').read_text())
    hashes = manifest['sha256']
    required = {'arm64-v8a/libdumber_chat_core.so', 'armeabi-v7a/libdumber_chat_core.so',
                'kotlin/src/main/kotlin/chat/dumber/sdk/ChatClient.kt',
                'kotlin/src/main/kotlin/chat/dumber/sdk/Models.kt', 'THIRD_PARTY_NOTICES.md'}
    if not required.issubset(hashes):
        raise ValueError('Incomplete native/binding manifest')
    for name, expected in hashes.items():
        path = bundle / name
        if not path.resolve().is_relative_to(bundle.resolve()) or not path.is_file():
            raise ValueError('Missing or invalid artifact: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Checksum mismatch: ' + name)
    for abi, elf_class, machine in [('arm64-v8a', 2, 183), ('armeabi-v7a', 1, 40)]:
        with (bundle / abi / 'libdumber_chat_core.so').open('rb') as library:
            header = library.read(20)
        if (len(header) != 20 or header[:4] != b'\x7fELF' or header[4:6] != bytes([elf_class, 1])
                or struct.unpack_from('<HH', header, 16) != (3, machine)):
            raise ValueError('Invalid shared-library architecture: ' + abi)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, default=Path(__file__).resolve().parents[1] / 'prebuilts/dumber-chat')
    args = parser.parse_args()
    try:
        verify(args.bundle)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'dumber.chat prebuilts: {error}. Sync the complete prebuilt bundle; no NDK or Cargo is needed.\n')
    print('dumber.chat ARM32/ARM64 libraries and Kotlin bindings verified')


if __name__ == '__main__':
    main()
