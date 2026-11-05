"""Expose a bounded log tail and concise failure lines in public CI annotations."""
import os
import re
import sys
from pathlib import Path
path=Path(sys.argv[1])
if path.is_file():
    raw=path.read_text(errors='replace')
    lines=re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', raw).splitlines()
    # Build jobs have no application tokens or signing keys in their environment.
    text='\n'.join(lines[-100:])[-20000:].replace('```', "'''")
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out:
        out.write('\n### '+path.name+'\n\n```text\n'+text+'\n```\n')

    # GitHub truncates long public annotations. Include concise failure lines
    # and the log's end even when no error pattern matches.
    important=[line for line in lines if re.search(
        r'error|panic|abort|failed|fatal|not found|core dumped|terminated|finished', line, re.I)]
    selected=list(dict.fromkeys(important[-6:]+lines[-6:]))[-12:]
    for line in selected:
        escaped=line[-880:].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
        print('::error title='+path.name+'::'+escaped)
