"""Use absolute MSVC tools, avoiding Git Bash's incompatible usr/bin/link.exe."""
import argparse
import os
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('target');p.add_argument('--legacy',action='store_true');args=p.parse_args()
vc=Path(os.environ['VCToolsInstallDir'])
sdk=Path(os.environ['WindowsSdkDir'])/'Lib'
version=os.environ.get('WindowsSDKLibVersion',os.environ.get('WindowsSDKVersion','')).strip('\\/')
sdk=sdk/version if version else sorted(sdk.iterdir())[-1]
arch='x86' if args.target.startswith('i686-') else 'x64'
host=vc/'bin/Hostx64/x64';target=vc/'bin/Hostx64'/arch
for file in (host/'link.exe', target/'link.exe',target/'cl.exe',target/'lib.exe'):
    if not file.is_file():raise SystemExit(f'MSVC tool missing: {file}')
flags=['-C','target-feature=+crt-static']
if args.legacy:flags+=['-C','link-arg=/SUBSYSTEM:WINDOWS,6.01']
for folder in (vc/'lib'/arch,sdk/'um'/arch,sdk/'ucrt'/arch):
    if not folder.is_dir():raise SystemExit(f'MSVC library directory missing: {folder}')
    flags+=['-C',f'link-arg=/LIBPATH:{folder}']
key=args.target.upper().replace('-','_');cc=args.target.replace('-','_')
values={f'CARGO_TARGET_{key}_LINKER':str(target/'link.exe'),
        'CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER':str(host/'link.exe'),
        f'CC_{cc}':str(target/'cl.exe'),f'AR_{cc}':str(target/'lib.exe'),
        'CARGO_ENCODED_RUSTFLAGS':'\x1f'.join(flags)}
with open(os.environ['GITHUB_ENV'],'a') as env:
    for key,value in values.items():env.write(f'{key}={value}\n')
print('Configured absolute MSVC tools for',args.target,'and x64 host')
