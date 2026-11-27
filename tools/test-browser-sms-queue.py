#!/usr/bin/env python3
"""Run the actual BrowserSmsQueue class with real Android SQLite and fake send boundaries.

Requires a connected adb device. Creates only a disposable /data/local/tmp database;
does not install APKs, touch application data, or send SMS.
"""
import os, pathlib, subprocess, tempfile, uuid
app=pathlib.Path(__file__).resolve().parent.parent
rom=app.parents[2]
adb=['adb']+(['-s',os.environ['ANDROID_SERIAL']] if os.environ.get('ANDROID_SERIAL') else [])
def run(cmd): subprocess.run([str(x) for x in cmd],check=True)
with tempfile.TemporaryDirectory(prefix='browser-sms-test-') as directory:
    root=pathlib.Path(directory);classes=root/'classes';classes.mkdir()
    android=rom/'prebuilts/sdk/current/public/android.jar'
    sources=list((app/'tests/browser-sms/src').rglob('*.java'))+[app/'src/com/android/messaging/dumber/BrowserSmsQueue.java']
    run(['javac','-cp',android,'-d',classes,*sources])
    run(['jar','cf',root/'test.jar','-C',classes,'.'])
    run([rom/'out/host/linux-x86/bin/d8','--lib',android,'--output',root/'test-dex.jar',root/'test.jar'])
    remote='/data/local/tmp/browser-sms-'+uuid.uuid4().hex
    try:
        run([*adb,'push',root/'test-dex.jar',remote+'.jar'])
        run([*adb,'shell','CLASSPATH='+remote+'.jar','app_process','/system/bin','com.android.messaging.dumber.BrowserSmsQueueTest',remote+'.db'])
    finally:
        run([*adb,'shell','rm','-f',remote+'.jar',remote+'.db',remote+'.db-wal',remote+'.db-shm',remote+'.db-journal'])
