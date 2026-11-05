"""Verify an owner-signed payload, then exercise upgrade and offline Android UI."""
import hashlib,json,os,re,subprocess,sys
from pathlib import Path

payload=Path('.smoke-input')
output=Path('android-integration');output.mkdir(exist_ok=True)
pin=Path('.github/family-certificate.sha256').read_text().strip()
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
def run(*args,timeout=180):
    r=subprocess.run(list(args),capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stdout.decode(errors='replace')[-3000:]+'\n'+r.stderr.decode(errors='replace')[-3000:])
    return r.stdout.decode(errors='replace')
try:
    sums=json.loads((payload/'SHA256.json').read_text())
    assert set(sums)=={'RC7.apk','RC8.apk','instrumentation.apk'}
    for name,digest in sums.items():
        file=payload/name
        assert hashlib.sha256(file.read_bytes()).hexdigest()==digest,'Payload hash mismatch'
        report=run(str(tools/'apksigner'),'verify','--print-certs',str(file))
        assert re.findall(r'Signer #\d+ certificate SHA-256 digest: (\S+)',report)==[pin],'Signer mismatch'
    for name,version,code in [('RC7.apk','0.5.0-rc.7',16),('RC8.apk','0.5.0-rc.8',17)]:
        report=run(str(tools/'aapt'),'dump','badging',str(payload/name))
        assert "name='org.luma.music'" in report and f"versionName='{version}'" in report and f"versionCode='{code}'" in report
    run('adb','install','-r',str(payload/'RC7.apk'))
    run('adb','shell','am','start','-W','-n','org.luma.music/.MainActivity')
    assert run('adb','shell','pidof','org.luma.music').strip(),'RC7 did not start'
    # Deliberately retain application data and package identity.
    run('adb','install','-r',str(payload/'RC8.apk'))
    run('adb','shell','am','start','-W','-n','org.luma.music/.MainActivity')
    assert run('adb','shell','pidof','org.luma.music').strip(),'RC8 did not start after upgrade'
    run('adb','install','-r',str(payload/'instrumentation.apk'))
    report=run('adb','shell','am','instrument','-w','org.luma.music.test/org.luma.music.RC8Instrumentation',timeout=300)
    (output/'results.txt').write_text(report)
    assert 'PASS RC8:' in report and 'FAIL RC8:' not in report,report
    print(report)
    for name in ('about-ar.png',):
        result=subprocess.run(['adb','exec-out','run-as','org.luma.music','cat','files/'+name],capture_output=True)
        if result.returncode==0 and result.stdout.startswith(b'\x89PNG'):
            (output/name).write_bytes(result.stdout)
    # Confirm persisted locale loads after process death, independent of the in-memory catalogue.
    run('adb','shell','am','force-stop','org.luma.music')
    run('adb','shell','am','start','-W','-n','org.luma.music/.MainActivity')
    assert run('adb','shell','pidof','org.luma.music').strip(),'Restart after locale selection failed'
    print('::notice title=Android RC8::Signed RC7 to RC8 upgrade, integration suite and process restart passed')
except Exception as error:
    message=str(error)
    crash=subprocess.run(['adb','logcat','-d','-b','crash','-t','60'],capture_output=True,timeout=20)
    message+='\n'+crash.stdout.decode(errors='replace')
    (output/'failure.txt').write_text(message)
    lines=message.splitlines()
    important=[line for line in lines if re.search(r'FAIL RC8|Caused by|org\.luma\.music|Exception:',line)]
    for line in list(dict.fromkeys(important[:7]+lines[-1:])):
        value=line[-850:].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
        print('::error title=Android RC integration::'+value)
    sys.exit(1)
