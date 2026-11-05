"""Expose bounded compiler diagnostics in a workflow summary when a build fails."""
import os
import sys
from pathlib import Path
path=Path(sys.argv[1])
if path.is_file():
    lines=path.read_text(errors='replace').splitlines()[-100:]
    # Build jobs have no application tokens or signing keys in their environment.
    text='\n'.join(lines)[-20000:].replace('```', "'''")
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out:
        out.write('\n### Compiler diagnostics\n\n```text\n'+text+'\n```\n')
