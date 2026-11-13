#!/usr/bin/env python3
"""Maintainer-only: build a complete prebuilt bundle from an external core checkout."""
import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hashes(core, workspace):
    paths = [core / 'Cargo.toml', workspace / 'Cargo.lock']
    paths += sorted((core / 'src').rglob('*.rs'))
    paths += sorted(p for p in (core / 'bindings/kotlin').rglob('*') if p.is_file())
    hashes = {str(p.relative_to(core)) if p.is_relative_to(core) else p.name: sha(p) for p in paths}
    hashes['workspace-Cargo.toml'] = sha(workspace / 'Cargo.toml')
    return hashes


def notices(metadata):
    packages = {p['id']: p for p in metadata['packages']}
    nodes = {n['id']: n for n in metadata['resolve']['nodes']}
    pending = [metadata['resolve']['root'] or next(p['id'] for p in packages.values()
                                                  if p['name'] == 'dumber-chat-core' and p['source'] is None)]
    selected = set()
    while pending:
        package = pending.pop()
        if package in selected:
            continue
        selected.add(package)
        pending += [d['pkg'] for d in nodes[package]['deps']
                    if any(k['kind'] != 'dev' for k in d['dep_kinds'])]
    output = ['# Third-party notices\n\nDependencies of the shipped Android JNI library.\n'
              'This includes build-time dependencies. The core source remains in dumber.chat.\n']
    for package in sorted((packages[p] for p in selected if packages[p]['source']), key=lambda p: (p['name'], p['version'])):
        output.append(f"\n## {package['name']} {package['version']}\n\nLicense: {package.get('license') or 'See license file'}\n")
        if package.get('repository'):
            output.append('Source: ' + package['repository'] + '\n')
        root = Path(package['manifest_path']).parent
        texts = set()
        for path in sorted(root.rglob('*')):
            if path.is_file() and path.name.lower().startswith(('license', 'licence', 'copying', 'notice', 'unlicense', 'copyright')):
                text = path.read_text(errors='replace')
                if text in texts:
                    continue
                texts.add(text)
                output.append('\n### ' + str(path.relative_to(root)) + '\n\n```text\n' + text.rstrip() + '\n```\n')
    return ''.join(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path, help='External dumber.chat/crates/chat-core directory')
    parser.add_argument('--ndk', type=Path, default=Path(os.environ.get('ANDROID_NDK_HOME',
        str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk'))) / 'ndk/30.0.16248370'))))
    args = parser.parse_args()
    core = args.source.resolve()
    app = Path(__file__).resolve().parents[1]
    toolchain = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    if not (core / 'Cargo.toml').is_file() or not (toolchain / 'llvm-strip').is_file():
        parser.error('Maintainer rebuilds require an external core checkout and a complete Linux NDK')
    cargo = ['cargo', '--manifest-path', str(core / 'Cargo.toml')]
    metadata = json.loads(subprocess.check_output([cargo[0], 'metadata', *cargo[1:], '--locked',
        '--format-version', '1', '--filter-platform', 'aarch64-linux-android']))
    workspace = Path(metadata['workspace_root'])
    before = source_hashes(core, workspace)
    env = dict(os.environ)
    target = Path(env.get('CARGO_TARGET_DIR', str(app / '.dumber-target'))).resolve()
    env['CARGO_TARGET_DIR'] = str(target)
    destination = app / 'prebuilts/dumber-chat'
    destination.parent.mkdir(parents=True, exist_ok=True)
    backup = destination.with_name('dumber-chat.previous')
    if backup.exists():
        parser.error('Resolve the existing dumber-chat.previous backup before updating')
    with tempfile.TemporaryDirectory(prefix='.dumber-chat-update-', dir=destination.parent) as temp:
        bundle = Path(temp) / 'bundle'
        bundle.mkdir()
        for abi, triple, compiler in [('arm64-v8a', 'aarch64-linux-android', 'aarch64-linux-android'),
                                      ('armeabi-v7a', 'armv7-linux-androideabi', 'armv7a-linux-androideabi')]:
            key = triple.replace('-', '_')
            build_env = dict(env, **{
                f'CARGO_TARGET_{key.upper()}_LINKER': str(toolchain / f'{compiler}23-clang'),
                f'CC_{key}': str(toolchain / f'{compiler}23-clang'),
                f'CXX_{key}': str(toolchain / f'{compiler}23-clang++'),
                f'AR_{key}': str(toolchain / 'llvm-ar')})
            subprocess.run([cargo[0], 'build', *cargo[1:], '--locked', '--profile', 'android-size', '--target', triple], env=build_env, check=True)
            (bundle / abi).mkdir()
            subprocess.run([str(toolchain / 'llvm-strip'), '--strip-unneeded', '-o',
                str(bundle / abi / 'libdumber_chat_core.so'), str(target / triple / 'android-size/libdumber_chat_core.so')], check=True)
        shutil.copytree(core / 'bindings/kotlin', bundle / 'kotlin')
        (bundle / 'THIRD_PARTY_NOTICES.md').write_text(notices(metadata))
        (bundle / 'README.md').write_text(
            '# dumber.chat Android prebuilts\n\n'
            'ARM64 and ARM32 JNI libraries and their matching Kotlin bindings.\n'
            'Normal ROM/app builds use these files directly; no Cargo or NDK is required.\n'
            'Keep all files and manifest.json together when updating.\n\n'
            'Maintainers: see ../../docs/dumber-chat.md and tools/update-dumber-chat-prebuilts.py.\n'
            'Rust source is maintained separately in dumber.chat/crates/chat-core.\n')
        if before != source_hashes(core, workspace):
            raise RuntimeError('Source changed during build; refusing to publish mismatched artifacts')
        manifest = {
            'format': 1, 'cargo_profile': 'android-size', 'built_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'source': 'dumber.chat/crates/chat-core', 'source_sha256': before,
            'rustc': subprocess.check_output(['rustc', '--version'], text=True).strip(),
            'ndk': (args.ndk / 'source.properties').read_text().strip(), 'android_min_api': 23,
            'sha256': {str(p.relative_to(bundle)): sha(p) for p in sorted(bundle.rglob('*')) if p.is_file()}}
        (bundle / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        subprocess.run(['python3', str(app / 'tools/verify-dumber-chat-prebuilts.py'), '--bundle', str(bundle)], check=True)
        if destination.exists():
            destination.rename(backup)
        try:
            bundle.rename(destination)
        except BaseException:
            if backup.exists():
                backup.rename(destination)
            raise
        if backup.exists():
            shutil.rmtree(backup)
    print('Updated both native ABIs, Kotlin bindings, notices and provenance. Commit the entire bundle together.')


if __name__ == '__main__':
    main()
