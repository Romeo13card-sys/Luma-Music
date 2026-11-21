#!/usr/bin/env python3
"""Verify the signed APK transport payload; no private signing key is needed in CI."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from release_identity import release_identity


def verify(root: Path):
    payload = root / '.release-input'
    info = json.loads((payload / 'release.json').read_text())
    tag, commit, version = info['tag'], info['commit'], info['version']
    preview = release_identity(tag, version)
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('Invalid source commit')
    if not isinstance(info.get('versionCode'), int) or info['versionCode'] <= 0:
        raise ValueError('Invalid versionCode')

    tagged_commit = subprocess.check_output(
        ['git', 'rev-parse', f'{tag}^{{commit}}'], cwd=root, text=True
    ).strip()
    if tagged_commit != commit:
        raise ValueError('Release tag does not point to the declared source commit')
    subprocess.run(['git', 'merge-base', '--is-ancestor', commit, 'HEAD'], cwd=root, check=True)
    subprocess.run(
        ['git', 'diff', '--quiet', commit, 'HEAD', '--',
         'android', 'core', 'locales', 'vendor', 'Cargo.toml', 'Cargo.lock',
         'scripts/build-android.sh', 'CHANGELOG.md'],
        cwd=root, check=True
    )

    expected = (root / '.github/family-certificate.sha256').read_text().strip().lower()
    supplied = (payload / 'certificate.sha256').read_text().strip().lower()
    if not re.fullmatch(r'[a-f0-9]{64}', expected) or supplied != expected:
        raise ValueError('Certificate pin mismatch')

    abis = {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal'}
    required = {f'Luma-Music-{version}-{abi}.apk' for abi in abis}
    apks = list(payload.glob('*.apk'))
    if {p.name for p in apks} != required or len(apks) != len(required):
        raise ValueError('Incomplete or unexpected APK set')

    sums = {}
    for line in (payload / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        if name in sums or name not in required or not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise ValueError('Invalid checksum list')
        sums[name] = digest
    if set(sums) != required:
        raise ValueError('Missing APK checksums')

    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    for apk in apks:
        if not 0 < apk.stat().st_size <= 100 * 1024 * 1024:
            raise ValueError(f'APK size invalid: {apk.name}')
        if hashlib.sha256(apk.read_bytes()).hexdigest() != sums[apk.name]:
            raise ValueError(f'APK checksum mismatch: {apk.name}')

        report = subprocess.check_output(
            [str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True
        )
        fingerprints = [
            value.replace(':', '').lower()
            for value in re.findall(
                r'Signer #\\d+ certificate SHA-256 digest: ([0-9A-Fa-f:]+)', report
            )
        ]
        if fingerprints != [expected]:
            raise ValueError(f'APK signer mismatch: {apk.name}')

        metadata = subprocess.check_output(
            [str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True
        )
        if not preview and (
            'application-debuggable' in metadata
            or re.search(r"\\btestOnly='true'", metadata)
        ):
            raise ValueError('Stable APK must be a non-debuggable production build')
        if (
            "name='org.luma.music'" not in metadata
            or f"versionName='{version}'" not in metadata
            or f"versionCode='{info['versionCode']}'" not in metadata
        ):
            raise ValueError(f'APK package/version mismatch: {apk.name}')
        subprocess.run(
            [str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)],
            check=True
        )

    return tag, commit


if __name__ == '__main__':
    tag, commit = verify(Path.cwd())
    info = json.loads((Path.cwd() / '.release-input/release.json').read_text())
    print(
        f'tag={tag}\\ncommit={commit}\\n'
        f'prerelease={str(release_identity(tag, info["version"])).lower()}\\n'
        f'version={info["version"]}'
    )
