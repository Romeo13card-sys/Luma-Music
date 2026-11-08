"""Verify an owner-signed payload, then exercise upgrade and offline Android UI."""
import hashlib,json,os,re,subprocess,sys,zipfile
from pathlib import Path

payload=Path('.smoke-input')
output=Path('android-integration');output.mkdir(exist_ok=True)
pin=Path('.github/family-certificate.sha256').read_text().strip()
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
stage='payload validation'
def run(*args,timeout=180):
    r=subprocess.run(list(args),capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stdout.decode(errors='replace')[-3000:]+'\n'+r.stderr.decode(errors='replace')[-3000:])
    return r.stdout.decode(errors='replace')
def launch(label):
    global stage
    stage=label
    print('Android launch stage: '+label,flush=True)
    run('adb','shell','am','start','-W','-n','org.luma.music/.MainActivity')

try:
    info=json.loads((payload/'source.json').read_text())
    source=info['commit']
    assert re.fullmatch(r'[a-f0-9]{40}',source),'Invalid compiled source identity'
    run('git','merge-base','--is-ancestor',source,'HEAD')
    run('git','diff','--quiet',source,'HEAD','--','android','core','locales','vendor','Cargo.toml','Cargo.lock','scripts/build-android.sh', 'CHANGELOG.md')
    with zipfile.ZipFile(payload/'app.apk') as apk:
        assert any(source.encode() in apk.read(n) for n in apk.namelist() if n.endswith('.dex')),'APK build identity differs from payload source'
    sums=json.loads((payload/'SHA256.json').read_text())
    assert set(sums)=={'baseline.apk','app.apk','instrumentation.apk'}
    for name,digest in sums.items():
        file=payload/name
        assert hashlib.sha256(file.read_bytes()).hexdigest()==digest,'Payload hash mismatch'
        report=run(str(tools/'apksigner'),'verify','--print-certs',str(file))
        assert re.findall(r'Signer #\d+ certificate SHA-256 digest: (\S+)',report)==[pin],'Signer mismatch'
    for name,identity in [('baseline.apk',info['baseline']),('app.apk',info['candidate'])]:
        version,code=identity['version'],identity['versionCode']
        report=run(str(tools/'aapt'),'dump','badging',str(payload/name))
        assert "name='org.luma.music'" in report and f"versionName='{version}'" in report and f"versionCode='{code}'" in report
    assert 'application-debuggable' not in report,'Release target is debuggable'
    assert info['candidate']['versionCode']>info['baseline']['versionCode'],'Upgrade must advance versionCode'
    run('adb','install','-r','-g',str(payload/'baseline.apk'))
    launch('baseline 0.5.0 startup')
    assert run('adb','shell','pidof','org.luma.music').strip(),'Baseline did not start'
    # Deliberately retain application data and package identity.
    run('adb','install','-r','-g',str(payload/'app.apk'))
    launch('candidate 1.0 startup')
    assert run('adb','shell','pidof','org.luma.music').strip(),'Release did not start after upgrade'
    run('adb','install','-r',str(payload/'instrumentation.apk'))
    stage='instrumentation checks'
    report=run('adb','shell','am','instrument','-w','org.luma.music.test/org.luma.music.LumaInstrumentation',timeout=300)
    (output/'results.txt').write_text(report)
    assert 'PASS Luma:' in report and 'FAIL Luma:' not in report,report
    print(report)
    for name in ('about-ar.png',):
        result=subprocess.run(['adb','exec-out','cat','/sdcard/Android/data/org.luma.music/files/'+name],capture_output=True)
        if result.returncode==0 and result.stdout.startswith(b'\x89PNG'):
            (output/name).write_bytes(result.stdout)
    # Confirm persisted locale loads after process death, independent of the in-memory catalogue.
    run('adb','shell','am','force-stop','org.luma.music')
    launch('candidate process restart')
    assert run('adb','shell','pidof','org.luma.music').strip(),'Restart after locale selection failed'
    print('::notice title=Android release::Signed baseline to release upgrade, integration suite and process restart passed')
except Exception as error:
    message=stage+': '+str(error)
    print('::error title=Android verification stage::'+stage)
    crash=subprocess.run(['adb','logcat','-d','-b','crash','-t','60'],capture_output=True,timeout=20)
    message+='\n'+crash.stdout.decode(errors='replace')
    for label,args in [('last ANR',['adb','shell','dumpsys','activity','lastanr']),('recent events',['adb','logcat','-d','-b','events','-t','30'])]:
        try:
            extra=subprocess.run(args,capture_output=True,timeout=15)
            message+='\n'+label+':\n'+extra.stdout.decode(errors='replace')[-6000:]
        except subprocess.TimeoutExpired:
            message+='\n'+label+': diagnostic command timed out'

    (output/'failure.txt').write_text(message)
    lines=message.splitlines()
    important=[line for line in lines if re.search(r'FAIL Luma|Caused by|org\.luma\.music|Exception:',line)]
    for line in list(dict.fromkeys(important[:7]+lines[-1:])):
        value=line[-850:].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
        print('::error title=Android release integration::'+value)
    sys.exit(1)
