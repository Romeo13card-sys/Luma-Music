"""Verify an owner-signed payload, then exercise upgrade and offline Android UI."""
import hashlib,json,os,re,subprocess,sys,zipfile
from pathlib import Path

payload=Path('.smoke-input')
output=Path('android-integration');output.mkdir(exist_ok=True)
pin=Path('.github/family-certificate.sha256').read_text().strip()
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
stage='payload validation'
def run(*args,timeout=180):
    try:
        r=subprocess.run(list(args),capture_output=True,timeout=timeout)
    except subprocess.TimeoutExpired as error:
        detail=(error.stdout or b'').decode(errors='replace')+(error.stderr or b'').decode(errors='replace')
        raise RuntimeError(str(error)+'; partial output: '+detail[-3000:]) from error
    if r.returncode:raise RuntimeError(r.stdout.decode(errors='replace')[-3000:]+'\n'+r.stderr.decode(errors='replace')[-3000:])
    return r.stdout.decode(errors='replace')
def launch(label):
    global stage
    stage=label
    print('Android launch stage: '+label,flush=True)
    print(run('adb','shell','am','start','-W','-a','android.intent.action.MAIN',
        '-c','android.intent.category.LAUNCHER','-n','org.luma.music/.MainActivity',timeout=60),flush=True)

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
    launch('baseline '+info['baseline']['version']+' startup')
    assert run('adb','shell','pidof','org.luma.music').strip(),'Baseline did not start'
    def installed_identity():
        report=run('adb','shell','dumpsys','package','org.luma.music')
        return tuple(re.search(r'\b'+field+r'=([^\r\n]+)',report).group(1).strip()
            for field in ('userId','firstInstallTime'))
    before=installed_identity()
    # Deliberately retain application data and package identity.
    stage='candidate install'
    print(run('adb','install','-r','-g',str(payload/'app.apk')),flush=True)
    assert installed_identity()==before,'Upgrade replaced the application UID or installation record'
    # A launcher cold start after replacement must not inherit the retired Activity task.
    # force-stop closes a process; it neither clears application data nor uninstalls the APK.
    run('adb','shell','am','force-stop','org.luma.music')
    launch('candidate '+info['candidate']['version']+' cold startup')
    assert run('adb','shell','pidof','org.luma.music').strip(),'Release did not start after upgrade'
    run('adb','install','-r',str(payload/'instrumentation.apk'))
    stage='instrumentation checks'
    report=run('adb','shell','am','instrument','-w','org.luma.music.test/org.luma.music.LumaInstrumentation',timeout=300)
    (output/'results.txt').write_text(report)
    assert 'PASS Luma:' in report and 'FAIL Luma:' not in report,report
    print(report)
    for name in ('about-ar.png','cover-dark.webp','cover-light.webp'):
        result=subprocess.run(['adb','exec-out','cat','/sdcard/Android/data/org.luma.music/files/'+name],capture_output=True)
        if result.returncode==0 and (result.stdout.startswith(b'\x89PNG') or result.stdout.startswith(b'RIFF')):
            (output/name).write_bytes(result.stdout)
            if name.startswith('cover-') and len(result.stdout)<40000:
                import base64
                print('::notice title='+name+'::'+base64.b64encode(result.stdout).decode())

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

    for label,args in [
        ('activity state',['adb','shell','dumpsys','activity','activities']),
        ('window state',['adb','shell','dumpsys','window','windows']),
        ('app log',['adb','logcat','-d','ActivityTaskManager:I','ActivityManager:I','PackageManager:I','AndroidRuntime:E','Luma:V','*:S']),
    ]:
        try:
            report=run(*args,timeout=15)
            (output/(label.replace(' ','-')+'.txt')).write_text(report)
            selected=[line for line in report.splitlines() if re.search(r'org[.]luma[.]music|ActivityTaskManager|Displayed|ANR|FATAL|mCurrentFocus|mFocusedApp|mResumedActivity',line)]
            for line in selected[-12:]:
                value=line[-1100:].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
                print('::warning title=Startup '+label+'::'+value)
        except Exception as diagnostic:
            print('Diagnostic failed: '+str(diagnostic),flush=True)
    (output/'failure.txt').write_text(message)
    lines=message.splitlines()
    important=[line for line in lines if re.search(r'FAIL Luma|Caused by|org\.luma\.music|Exception:',line)]
    for line in list(dict.fromkeys(important[:7]+lines[-1:])):
        value=line[-850:].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
        print('::error title=Android release integration::'+value)
    sys.exit(1)
