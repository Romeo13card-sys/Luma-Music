"""Validated version identity; display 1.0 and canonical 1.0.0 denote the same release."""
import re

_PATTERN = r'(?:[0-9]+\.[0-9]+\.[0-9]+(?:-(?:rc|test|dev)\.[0-9]+)?|[0-9]+\.[0-9]+)'

def canonical_version(version):
    if not re.fullmatch(_PATTERN, version):
        raise ValueError('Invalid release version')
    return version + '.0' if version.count('.') == 1 else version

def display_version(version):
    canonical_version(version)
    return version[:-2] if '-' not in version and version.endswith('.0') and version.count('.') == 2 else version

def release_identity(tag, version=None):
    if not tag.startswith('v'):
        raise ValueError('Invalid release tag/version')
    canonical = canonical_version(tag[1:])
    if version is not None and canonical != canonical_version(version):
        raise ValueError('Invalid release tag/version')
    return '-' in tag
