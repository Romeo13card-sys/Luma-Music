#!/usr/bin/env python3
"""Verify pre-signed APK transport; no private key or signing secrets needed in CI."""
import hashlib
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path
from release_identity import release_identity


def verify(root: Path):
    payload = root / '.release-input'
    info = json.loads((payload / 'release.json').read_text())
    tag, commit, version = info['tag'], info['commit'], info['version']
    preview = release_identity(tag, version)
    if not re.fullmatch(r'[a-f0-9]{40}', commit) or not re.fullmatch(r'[A-Za-z0-9._-]+', version):
        raise ValueError('Invalid release identity')
    subprocess.run(['git', 'merge-base', '--is-ancestor', commit, 'HEAD'], cwd=root, check=True)
    subprocess.run(['git', 'diff', '--quiet', commit, 'HEAD', '--', 'android', 'core', 'locales', 'vendor', 'Cargo.toml', 'Cargo.lock', 'scripts/build-android.sh', 'CHANGELOG.md'], cwd=root, check=True)
    expected = (root / '.github/family-certificate.sha256').read_text().strip()
    if not re.fullmatch(r'[a-f0-9]{64}', expected) or (payload/'certificate.sha256').read_text().strip() != expected:
        raise ValueError('Certificate pin mismatch')
    abis = {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal'}
    required = {f'Luma-Music-{version}-{abi}.apk' for abi in abis}
    apks = list(payload.glob('*.apk'))
    if {p.name for p in apks} != required:
        raise ValueError('Incomplete or unexpected APK set')
    sums = {}
    for line in (payload/'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        if name in sums or name not in required or not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise ValueError('Invalid checksum list')
        sums[name] = digest
    if set(sums) != required:
        raise ValueError('Missing APK checksums')
    tools = Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
    for apk in apks:
        if not 0 < apk.stat().st_size <= 100*1024*1024 or hashlib.sha256(apk.read_bytes()).hexdigest() != sums[apk.name]:
            raise ValueError('APK size/hash invalid')
        report = subprocess.check_output([str(tools/'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        if re.findall(r'Signer #\d+ certificate SHA-256 digest: (\S+)', report) != [expected]:
            raise ValueError('APK signer mismatch')
        metadata = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(apk)], text=True)
        if not preview and ('application-debuggable' in metadata or 'application-testOnly' in metadata):
            raise ValueError('Stable APK must be a non-debuggable production build')
        if not preview:
            with zipfile.ZipFile(apk) as archive:
                if not any(commit.encode() in archive.read(name) for name in archive.namelist() if name.endswith('.dex')):
                    raise ValueError('APK compiled source identity mismatch')
        if "name='org.luma.music'" not in metadata or f"versionName='{version}'" not in metadata or f"versionCode='{int(info['versionCode'])}'" not in metadata:
            raise ValueError('APK package/version mismatch')
        subprocess.run([str(tools/'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
    return tag, commit


if __name__ == '__main__':
    tag, commit = verify(Path.cwd())
    print(f'tag={tag}\ncommit={commit}\nprerelease={str(release_identity(tag)).lower()}\nversion={json.loads((Path.cwd()/".release-input/release.json").read_text())["version"]}')
