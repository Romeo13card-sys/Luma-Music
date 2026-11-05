#!/usr/bin/env python3
"""Detect accidental changes to the pinned, unmodified YAYMA reference snapshot."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / 'vendor/yayma'
expected = json.loads((root / 'vendor/YAYMA-SHA256.json').read_text())
actual = {str(p.relative_to(base)): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in base.rglob('*') if p.is_file()}
if actual != expected:
    raise SystemExit('YAYMA snapshot changed; keep adaptations in core/ or deliberately repin upstream')
if (root/'LICENSE').read_bytes() != (base/'LICENSE').read_bytes():
    raise SystemExit('GPL license text mismatch')
print(f'Pinned YAYMA snapshot verified: {len(actual)} files')
