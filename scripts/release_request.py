"""Read a validated release request while keeping every workflow on main."""
import json
import os
from pathlib import Path
from release_identity import release_identity

def requested_tag():
    if os.environ.get('GITHUB_REF_NAME') != 'main':
        raise ValueError('Release operations must run from main')
    tag = os.environ.get('RELEASE_TAG', '').strip()
    if not tag:
        tag = json.loads(Path('.github/release-request.json').read_text())['tag']
    release_identity(tag)
    return tag
