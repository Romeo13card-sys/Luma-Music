import unittest
from release_identity import release_identity, display_version


class ReleaseIdentityTests(unittest.TestCase):
    def test_stable_and_preview(self):
        self.assertFalse(release_identity('v0.5.0', '0.5.0'))
        self.assertTrue(release_identity('v0.5.0-rc.8', '0.5.0-rc.8'))
        self.assertTrue(release_identity('v0.4.0-test.2'))
        self.assertTrue(release_identity('v1.0.0-dev.1', '1.0.0-dev.1'))

    def test_short_display_and_legacy_ota_tag_share_identity(self):
        self.assertFalse(release_identity('v1.0.0', '1.0'))
        self.assertFalse(release_identity('v1.0', '1.0.0'))
        self.assertEqual(display_version('1.0.0'), '1.0')
        self.assertEqual(display_version('1.0.1'), '1.0.1')
        self.assertEqual(display_version('1.0.0-dev.1'), '1.0.0-dev.1')
        with self.assertRaises(ValueError):
            release_identity('v1.0.1', '1.0')

    def test_rejects_mismatched_and_unsafe_identity(self):
        for tag in ('v0', '0.5.0', 'v0.5.0-rc.8/../x', 'v0.5.0-rc', 'v0.5.0+extra'):
            with self.assertRaises(ValueError):
                release_identity(tag)
        with self.assertRaises(ValueError):
            release_identity('v0.5.0-rc.8', '0.5.0')
