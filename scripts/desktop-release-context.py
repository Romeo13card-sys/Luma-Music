"""Validate immutable source/tag identity for an owner-pushed desktop release transport."""
import os,re,subprocess,tomllib
from pathlib import Path
branch=os.environ['GITHUB_REF_NAME'];tag=branch.removeprefix('publish-desktop/')
if branch==tag or not re.fullmatch(r'v\d+\.\d+\.\d+-rc\.\d+',tag):raise SystemExit('Invalid desktop release branch')
version=tomllib.loads(Path('desktop/Cargo.toml').read_text())['package']['version']
if tag!='v'+version:raise SystemExit('Desktop version/tag mismatch')
source=subprocess.check_output(['git','rev-parse',f'{tag}^{{commit}}'],text=True).strip()
subprocess.run(['git','diff','--quiet',source,'HEAD'],check=True)
with open(os.environ['GITHUB_ENV'],'a') as env:env.write(f'RELEASE_TAG={tag}\nLUMA_RELEASE_SOURCE={source}\n')
print('Validated desktop release',tag,source)
