#!/usr/bin/env python3
"""Sign APKs outside CI using a persistent private key kept outside this checkout."""
import argparse
import hashlib
import os
import re
import secrets
import subprocess
from pathlib import Path


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'{Path(command[0]).name} failed; check tool installation and key configuration')
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--key-dir', type=Path, required=True)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--init-key', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    keydir = args.key_dir.resolve()
    if keydir.is_relative_to(root):
        raise ValueError('Private key must be outside the checkout')
    if args.input.resolve() == args.output.resolve():
        raise ValueError('Input and output must differ')
    keydir.mkdir(mode=0o700, parents=True, exist_ok=True)
    keydir.chmod(0o700)
    keystore = keydir / 'luma-family.p12'
    password = keydir / 'password'
    pin = keydir / 'certificate.sha256'
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    if not keystore.exists() or not password.exists():
        if not args.init_key or any(keydir.iterdir()):
            raise ValueError('Key unavailable or incomplete. Refusing automatic key replacement.')
        with password.open('x') as out:
            password.chmod(0o600)
            out.write(secrets.token_urlsafe(48))
        previous = os.umask(0o077)
        try:
            run([str(java/'keytool'), '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
                 '-alias', 'luma-family', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                 '-dname', 'CN=Luma Music Family', '-storepass:file', str(password), '-keypass:file', str(password)])
        finally:
            os.umask(previous)
    keystore.chmod(0o600)
    password.chmod(0o600)
    sources = sorted(args.input.glob('Luma-Music-*.apk'))
    if not sources:
        raise ValueError('No APKs to sign')
    args.output.mkdir(parents=True, exist_ok=True)
    fingerprints = set()
    sums = []
    for source in sources:
        destination = args.output/source.name
        run([str(sdk/'apksigner'), 'sign', '--ks', str(keystore), '--ks-key-alias', 'luma-family',
             '--ks-pass', f'file:{password}', '--out', str(destination), str(source)])
        report = run([str(sdk/'apksigner'), 'verify', '--print-certs', str(destination)])
        match = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})', report)
        if not match:
            raise ValueError('No signing certificate')
        fingerprints.add(match[1].lower())
        run([str(sdk/'zipalign'), '-c', '-P', '16', '4', str(destination)])
        sums.append(f'{hashlib.sha256(destination.read_bytes()).hexdigest()}  {destination.name}\n')
    if len(fingerprints) != 1:
        raise ValueError('Inconsistent signing certificates')
    fingerprint = fingerprints.pop()
    if pin.exists() and pin.read_text().strip() != fingerprint:
        raise ValueError('Key identity changed; refusing publication')
    pin.write_text(fingerprint+'\n')
    (args.output/'certificate.sha256').write_text(fingerprint+'\n')
    (args.output/'SHA256SUMS.txt').write_text(''.join(sums))
    print('Signed APKs:', args.output)
    print('Private key retained outside Git. Back up its protected directory securely.')


if __name__ == '__main__':
    main()
