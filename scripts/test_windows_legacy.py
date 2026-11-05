import importlib.util
import unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('audit',Path(__file__).with_name('audit-windows-legacy.py'))
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class LegacyAudit(unittest.TestCase):
    def test_rejects_new_imports_and_bad_subsystem(self):
        for subsystem,imports in [((10,0),[]),((6,1),[('bcryptprimitives.dll',set())]),((6,1),[('kernel32.dll',{'WaitOnAddress'})])]:
            with self.assertRaises(ValueError):audit.validate(subsystem,imports)
        audit.validate((6,1),[('kernel32.dll',{'GetProcAddress','LoadLibraryW'})])
