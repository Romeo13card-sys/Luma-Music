"""Strict shared identity for stable and preview releases."""
import re


def release_identity(tag, version=None):
    match = re.fullmatch(r'v(\d+\.\d+\.\d+)(-(?:rc|test)\.\d+)?', tag)
    if not match or (version is not None and tag != 'v' + version):
        raise ValueError('Invalid release tag/version')
    return bool(match.group(2))
