"""Upload desktop assets immutably; keep existing verified builds of the same source."""
import hashlib,json,re,subprocess,tempfile,zipfile
from pathlib import Path
from release_identity import release_identity, display_version
from release_request import requested_tag

def gh(*args):return subprocess.check_output(['gh',*args],text=True)
def metadata(path):
    with zipfile.ZipFile(path) as z:
        if z.getinfo('build-info.json').file_size>65536:raise ValueError('Oversized build identity')
        return json.loads(z.read('build-info.json'))

tag=requested_tag()
release_identity(tag)
source=subprocess.check_output(['git','rev-parse',f'{tag}^{{commit}}'],text=True).strip()
release=json.loads(gh('release','view',tag,'--json','assets'))
existing={a['name'] for a in release['assets']}
for file in sorted(Path('release').glob('*.zip')):
    local=metadata(file)
    if local['source']!=source or local['version']!=display_version(tag[1:]):raise SystemExit('Artifact source/version mismatch')
    checksum=file.with_name(file.name+'.sha256')
    if hashlib.sha256(file.read_bytes()).hexdigest()!=checksum.read_text().split()[0]:raise SystemExit('Artifact checksum mismatch')
    if file.name in existing:
        with tempfile.TemporaryDirectory() as tmp:
            gh('release','download',tag,'--pattern',file.name,'--dir',tmp)
            old=Path(tmp)/file.name;remote=metadata(old)
            if any(remote.get(k)!=local.get(k) for k in ('source','version','platform','arch','legacy_experimental')):
                raise SystemExit('Existing artifact identity differs; refusing replacement')
            digest=hashlib.sha256(old.read_bytes()).hexdigest()
            if checksum.name in existing:
                gh('release','download',tag,'--pattern',checksum.name,'--dir',tmp)
                if (Path(tmp)/checksum.name).read_text().split()[0]!=digest:raise SystemExit('Published checksum invalid')
            else:
                summary=Path(tmp)/checksum.name;summary.write_text(f'{digest}  {file.name}\n')
                gh('release','upload',tag,str(summary))
        print('Kept verified existing build:',file.name)
    else:
        gh('release','upload',tag,str(file))
        # ZIP first: an interrupted upload can safely resume without an orphan checksum.
        gh('release','upload',tag,str(checksum))
        print('Published:',file.name)
