"""Archive native previews with license and Unix executable permissions intact."""
import os
import zipfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
binary = root / 'target/release' / ('luma-desktop.exe' if os.name == 'nt' else 'luma-desktop')
if not binary.is_file():
    raise SystemExit('Desktop binary missing')
with zipfile.ZipFile(root/'Luma-Music-desktop-preview.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.write(binary, binary.name)
    for name in ('LICENSE', 'NOTICE', 'docs/PLATFORMS.md'):
        archive.write(root/name, name)
