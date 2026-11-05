"""Package release candidates with platform identity, source SHA and executable permissions."""
import argparse
import hashlib
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--platform')
    parser.add_argument('--arch')
    parser.add_argument('--legacy', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    version = tomllib.loads((root/'desktop/Cargo.toml').read_text())['package']['version']
    platform = args.platform or {'nt': 'Windows-10-11'}.get(os.name, 'macOS' if sys.platform == 'darwin' else 'Linux')
    arch = args.arch or os.environ.get('RUNNER_ARCH', 'X64').lower()
    if any(not re.fullmatch(r'[A-Za-z0-9._-]+', v) for v in (version, platform, arch)):
        raise SystemExit('Unsafe package identity')
    binary = (args.binary or root/'target/release'/('luma-desktop.exe' if os.name == 'nt' else 'luma-desktop')).resolve()
    result = subprocess.run([str(binary), '--self-check'], capture_output=True, text=True, timeout=20, check=True)
    identity = json.loads(result.stdout)
    if identity['version'] != version or bool(identity['legacy_windows']) != args.legacy:
        raise SystemExit('Binary version/legacy target mismatch')
    source = os.environ.get('LUMA_RELEASE_SOURCE') or subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
    if not re.fullmatch(r'[a-f0-9]{40}', source):
        raise SystemExit('Invalid source commit')
    output = root/'dist'; output.mkdir(exist_ok=True)
    suffix = '-experimental' if args.legacy else ''
    filename = f'Luma-Music-{version}-{platform}-{arch}{suffix}.zip'
    recipe = subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
    metadata = dict(version=version, platform=platform, arch=arch, source=source, build_recipe=recipe, binary=identity,
                    legacy_experimental=args.legacy, runtime_test='--self-check on build host; real devices required')
    with tempfile.TemporaryDirectory() as temp:
        stage = Path(temp)
        if platform == 'macOS':
            app = stage/'Luma Music.app'; executable = app/'Contents/MacOS/LumaMusic'
            executable.parent.mkdir(parents=True); shutil.copyfile(binary, executable); executable.chmod(0o755)
            with (app/'Contents/Info.plist').open('wb') as out:
                plistlib.dump(dict(CFBundleExecutable='LumaMusic', CFBundleIdentifier='org.luma.music.desktop',
                    CFBundleName='Luma Music', CFBundlePackageType='APPL', CFBundleShortVersionString=version.split('-')[0],
                    CFBundleVersion=version.split('-')[0], LumaVersion=version, NSHighResolutionCapable=True, LSMinimumSystemVersion='11.0'), out)
            subprocess.run(['codesign','--force','--sign','-','--timestamp=none',str(app)], check=True)
            subprocess.run(['codesign','--verify',str(app)], check=True)
        else:
            executable = stage/binary.name; shutil.copyfile(binary, executable); executable.chmod(0o755)
        for name in ('LICENSE','NOTICE','docs/PLATFORMS.md'):
            target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/name,target)
        (stage/'build-info.json').write_text(json.dumps(metadata, indent=2)+'\n')
        archive = output/filename
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as out:
            for file in sorted(stage.rglob('*')):
                if not file.is_file(): continue
                info=zipfile.ZipInfo(file.relative_to(stage).as_posix(), (1980,1,1,0,0,0))
                info.create_system=3; info.external_attr=(0o100755 if os.access(file,os.X_OK) else 0o100644)<<16
                out.writestr(info,file.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
        digest=hashlib.sha256(archive.read_bytes()).hexdigest()
        (output/(filename+'.sha256')).write_text(f'{digest}  {filename}\n')
    print(archive)

if __name__=='__main__': main()
