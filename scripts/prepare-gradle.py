#!/usr/bin/env python3
"""Seed the wrapper ZIP from official Gradle hosts; the pinned SHA remains mandatory."""
import hashlib
import os
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlparse

root = Path(__file__).resolve().parents[1]
properties = dict(line.split('=', 1) for line in
    (root/'android/gradle/wrapper/gradle-wrapper.properties').read_text().splitlines()
    if '=' in line and not line.startswith('#'))
url = properties['distributionUrl'].replace('\\:', ':')
parsed = urlparse(url)
name = Path(parsed.path).name
match = re.fullmatch(r'gradle-(\d+(?:\.\d+){1,2})-bin\.zip', name)
expected = properties.get('distributionSha256Sum', '')
if parsed.scheme != 'https' or parsed.hostname not in {'downloads.gradle.org', 'services.gradle.org'} or not match or not re.fullmatch('[a-f0-9]{64}', expected):
    raise SystemExit('A pinned official Gradle distribution is required')
# Gradle PathAssembler uses base36(MD5(URL)) as a cache directory, not as an integrity check.
number = int(hashlib.md5(url.encode()).hexdigest(), 16)
alphabet = '0123456789abcdefghijklmnopqrstuvwxyz'
key = ''
while number:
    number, digit = divmod(number, 36)
    key = alphabet[digit] + key
home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle')))
cache = home/'wrapper/dists'/name[:-4]/(key or '0')
if (cache/(name+'.ok')).is_file() and (cache/('gradle-'+match[1])/'bin/gradle').is_file():
    print('Gradle wrapper distribution already cached')
    raise SystemExit(0)
cache.mkdir(parents=True, exist_ok=True)
urls = list(dict.fromkeys([url, 'https://services.gradle.org/distributions/'+name]))
for candidate in urls:
    with tempfile.NamedTemporaryFile(dir=cache, suffix='.download', delete=False) as out:
        temporary = Path(out.name)
    try:
        result = subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
            '--retry', '2', '--retry-all-errors', '--retry-max-time', '240',
            '--connect-timeout', '15', '--max-time', '120', '--output', str(temporary), candidate])
        if result.returncode:
            continue
        digest = hashlib.sha256()
        with temporary.open('rb') as incoming:
            for chunk in iter(lambda: incoming.read(1024*1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise SystemExit('Gradle distribution checksum mismatch')
        os.replace(temporary, cache/name)
        print('Pinned Gradle ZIP prepared; wrapper will verify and extract it')
        break
    finally:
        temporary.unlink(missing_ok=True)
else:
    raise SystemExit('Could not download Gradle from either official host')
