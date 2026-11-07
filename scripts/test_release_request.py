import os
import unittest
from unittest.mock import patch
from release_request import requested_tag

class ReleaseRequestTests(unittest.TestCase):
    def test_main_accepts_stable_and_development_tag(self):
        for tag in ['v0.5.0', 'v1.0.0-dev.1']:
            with patch.dict(os.environ, GITHUB_REF_NAME='main', RELEASE_TAG=tag):
                self.assertEqual(requested_tag(), tag)

    def test_other_branches_and_unsafe_tags_are_rejected(self):
        for branch, tag in [('work', 'v1.0.0-dev.1'), ('main', '../v1'), ('main', 'v1.0.0\nbad')]:
            with patch.dict(os.environ, GITHUB_REF_NAME=branch, RELEASE_TAG=tag):
                with self.assertRaises(ValueError):
                    requested_tag()
