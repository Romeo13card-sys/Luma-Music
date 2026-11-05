#!/usr/bin/env python3
"""Name distributable APKs using Gradle metadata, keeping Gradle outputs intact."""
import hashlib
import json
import re
import shutil
from pathlib import Path


def package(source: Path, destination: Path) -> Path:
    metadata = json.loads((source / 'output-metadata.json').read_text())
    entries = metadata['elements']
    versions = {e['versionName'] for e in entries}
    if len(versions) != 1:
        raise ValueError('All APKs must have the same version')
    version = versions.pop()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', version):
        raise ValueError('Unsafe version name')
    planned = {}
    for entry in entries:
        abi_filters = [f['value'] for f in entry.get('filters', []) if f['filterType'] == 'ABI']
        abi = abi_filters[0] if len(abi_filters) == 1 else 'universal'
        if len(abi_filters) > 1 or abi not in {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal'} or abi in planned:
            raise ValueError('Unexpected or duplicate APK architecture')
        apk = (source / entry['outputFile']).resolve()
        if not apk.is_relative_to(source.resolve()) or not apk.is_file():
            raise ValueError('APK missing or outside build directory')
        planned[abi] = apk
    if set(planned) != {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal'}:
        raise ValueError('Expected four architectures and universal APKs')
    output = destination / version
    output.mkdir(parents=True, exist_ok=True)
    sums = []
    for abi, apk in sorted(planned.items()):
        target = output / f'Luma-Music-{version}-{abi}.apk'
        shutil.copyfile(apk, target)
        sums.append(f'{hashlib.sha256(target.read_bytes()).hexdigest()}  {target.name}\n')
    (output / 'SHA256SUMS.txt').write_text(''.join(sums))
    return output


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    output = package(root / 'android/app/build/outputs/apk/debug', root / 'android/app/build/outputs/distribution')
    print(f'directory={output.relative_to(root)}')
