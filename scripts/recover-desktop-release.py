"""Resume successful artifacts without rebuilding or replacing published binaries."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

branch = os.environ['GITHUB_REF_NAME']
match = re.fullmatch(r'resume-desktop/(\d+)/(v\d+\.\d+\.\d+-rc\.\d+)', branch)
if not match:
    raise SystemExit('Invalid recovery branch')
run_id, tag = match.groups()
repo = os.environ['GITHUB_REPOSITORY']
env = dict(os.environ, GITHUB_REF_NAME='publish-desktop/' + tag, RELEASE_BRANCH=branch)
subprocess.run([sys.executable, 'scripts/desktop-release-context.py'], env=env, check=True)


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True)


run = json.loads(gh('api', f'repos/{repo}/actions/runs/{run_id}'))
if run['name'] != 'Desktop release' or not run['head_branch'].startswith('publish-desktop/'):
    raise SystemExit('Recovery requires an owner-pushed desktop release run')
if run['head_branch'].rsplit('/', 1)[-1] != tag:
    raise SystemExit('Recovery run tag mismatch')

jobs = json.loads(gh('api', f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100'))['jobs']
for job in jobs:
    if job['conclusion'] != 'failure' or not job['name'].startswith('desktop ('):
        continue
    # The completed job endpoint is available even when another matrix job is running.
    log = gh('api', f'repos/{repo}/actions/jobs/{job["id"]}/logs')
    lines = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', log).splitlines()
    pattern = r'error:|LLVM ERROR|panicked|Aborted|core dumped|malloc|No space left|out of memory|double free|corrupted'
    selected = [line for line in lines if re.search(pattern, line, re.I)][-6:]
    for line in selected:
        value = line[-850:].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::warning title=Failed desktop job::' + value, flush=True)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('\n### ' + job['name'] + '\n\n```text\n' +
                      '\n'.join(selected)[-6000:].replace('```', "'''") + '\n```\n')

artifacts = json.loads(gh('api', f'repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100'))['artifacts']
downloaded = 0
for artifact in artifacts:
    if artifact['expired'] or not artifact['name'].startswith('release-'):
        continue
    subprocess.run(['gh', 'run', 'download', run_id, '--name', artifact['name'], '--dir', 'release'], check=True)
    downloaded += 1
if not downloaded or not list(Path('release').glob('*.zip')):
    raise SystemExit('No successful modern desktop artifacts to recover')
subprocess.run([sys.executable, 'scripts/publish-desktop-assets.py'], env=env, check=True)
