#!/usr/bin/env python3
"""Run the local demo smoke test on an already booted emulator/device (no account)."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get('ADB', 'adb')
ROOT = Path(__file__).resolve().parents[1]

def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True, timeout=45).stdout

def tap(label):
    for _ in range(5):
        adb('shell', 'uiautomator', 'dump', '/sdcard/glass-ui.xml')
        xml = ET.fromstring(adb('exec-out', 'cat', '/sdcard/glass-ui.xml'))
        for node in xml.iter('node'):
            if node.get('text') == label:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                if x2 > x1 and y2 > y1:
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    return
        adb('shell', 'input', 'swipe', '240', '750', '240', '250', '350')
    raise AssertionError('Missing visible control: ' + label)

assert adb('shell', 'getprop', 'sys.boot_completed').strip() == b'1', 'Android has not finished booting'
adb('shell', 'am', 'force-stop', 'org.luma.music')
adb('shell', 'am', 'start', '-W', '-n', 'org.luma.music/.MainActivity')
tap('Попробовать без аккаунта')
adb('shell', 'screencap', '-p', '/sdcard/glass-home.png')
out = ROOT / 'android/app/build/outputs/smoke'
out.mkdir(parents=True, exist_ok=True)
adb('pull', '/sdcard/glass-home.png', str(out/'glass-home.png'))
tap('Слушать тестовый звук')
time.sleep(1)
# Verify a real active session, rather than only an open activity or service PID.
sessions = adb('shell', 'dumpsys', 'media_session').decode(errors='replace')
assert 'org.luma.music' in sessions and 'Luma · тестовый звук' in sessions, sessions
assert 'state=3' in sessions, 'Demo audio did not reach PLAYING'
adb('shell', 'input', 'keyevent', '3')
sessions = adb('shell', 'dumpsys', 'media_session').decode(errors='replace')
assert 'state=3' in sessions, 'Playback stopped when application went into background'
adb('shell', 'am', 'start', '-W', '-n', 'org.luma.music/.MainActivity')
print('PASS: demo navigation, playback session and foreground playback while UI is in background')
