"""Validate immutable source/tag identity for an owner-pushed desktop release transport."""
import os,re,subprocess,tomllib
from pathlib import Path
from release_identity import release_identity
from release_request import requested_tag
tag=requested_tag()
version=tomllib.loads(Path('desktop/Cargo.toml').read_text())['package']['version']
release_identity(tag,version)
source=subprocess.check_output(['git','rev-parse',f'{tag}^{{commit}}'],text=True).strip()
subprocess.run(['git','merge-base','--is-ancestor',source,'HEAD'],check=True)
subprocess.run(['git','diff','--quiet',source,'HEAD','--','core','desktop','locales','vendor','Cargo.toml','Cargo.lock','LICENSE','NOTICE'],check=True)
with open(os.environ['GITHUB_ENV'],'a') as env:env.write(f'RELEASE_TAG={tag}\nLUMA_RELEASE_SOURCE={source}\n')
print('Validated desktop release',tag,source)
