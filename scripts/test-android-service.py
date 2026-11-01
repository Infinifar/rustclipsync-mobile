#!/usr/bin/env python3
"""Run a fresh Android emulator against a pinned disposable server; never use a real device."""
import argparse
import base64
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
APP = 'io.github.infinifar.rustclipsync.disposable'
NORMAL = 'io.github.infinifar.rustclipsync'
TEST_CLASS = NORMAL + '.ServiceIntegrationTest'
SERIAL = 'emulator-5554'
DEVICE_PORT = 18443
USER, PASSWORD = 'synthetic-mobile', 'synthetic-mobile-password'
OBSERVER = '90000000-0000-4000-8000-000000000001'
TOKEN = '44' * 32


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-cli', default=str(Path.home() / '.local/bin/android'))
    args = parser.parse_args()
    sdk = Path(os.environ['ANDROID_HOME'])
    adb = str(sdk / 'platform-tools/adb')
    android = [args.android_cli, '--no-metrics', '--sdk=' + str(sdk)]
    output = ROOT / 'build/service-e2e'
    output.mkdir(parents=True, exist_ok=True)
    binary = ROOT / 'build/disposable-server/rustclipsync-server'
    provenance = json.loads(binary.with_name('provenance.json').read_text())
    assert hashlib.sha256(binary.read_bytes()).hexdigest() == provenance['binary_sha256']
    expected = json.loads((ROOT / 'tests/disposable-server-source.json').read_text())
    assert all(provenance[k] == value for k, value in expected.items())
    apks = [ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk',
            ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
            ROOT / 'build/disposable/app-disposable.apk',
            ROOT / 'build/disposable/app-disposable-androidTest.apk']
    report = {'server': provenance, 'apks': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks},
              'completed': [], 'faults': [], 'requests': []}
    with tempfile.TemporaryDirectory(prefix='rcs-android-service-') as temporary:
        temporary = Path(temporary)
        environment = dict(os.environ, ANDROID_USER_HOME=str(temporary / 'user'), ANDROID_AVD_HOME=str(temporary / 'avd'))
        for key in ['ANDROID_USER_HOME', 'ANDROID_AVD_HOME']: Path(environment[key]).mkdir()

        def run(command, **kw):
            return subprocess.run(command, env=environment, check=True, timeout=180, **kw)

        def device(*arguments, check=True, timeout=60):
            return subprocess.run([adb, '-s', SERIAL, *arguments], env=environment,
                                  check=check, timeout=timeout, capture_output=True, text=True)

        with socket.socket() as allocation:
            allocation.bind(('127.0.0.1', 0)); backend_port = allocation.getsockname()[1]
        backend = None
        proxy = None
        proxy_thread = None
        crash_process = None
        dropping = threading.Event()
        backend_log = (output / 'server.log').open('w')

        def start_backend():
            nonlocal backend
            settings = {'PATH': os.environ['PATH'], 'RCS_USER': USER, 'RCS_PASSWORD': PASSWORD,
                        'RCS_DB_KEY': '2a' * 32, 'RCS_DATA_DIR': str(temporary / 'data'),
                        'RCS_LISTEN': f'127.0.0.1:{backend_port}'}
            backend = subprocess.Popen([str(binary)], env=settings, stdout=backend_log, stderr=backend_log)
            for _ in range(200):
                if backend.poll() is not None: raise RuntimeError('disposable server exited')
                try:
                    connection = http.client.HTTPConnection('127.0.0.1', backend_port, timeout=1)
                    connection.request('GET', '/healthz')
                    response = connection.getresponse(); response.read(); connection.close()
                    if response.status == 200: return
                except OSError: pass
                time.sleep(.1)
            raise RuntimeError('disposable server startup timeout')

        def stop_backend():
            if backend is not None:
                backend.terminate()
                try: backend.wait(timeout=5)
                except subprocess.TimeoutExpired: backend.kill(); backend.wait(timeout=5)

        def metadata(path='/api/state', basic=False, method='GET', body=None):
            headers = {'Authorization': ('Basic ' + base64.b64encode(f'{USER}:{PASSWORD}'.encode()).decode()) if basic
                       else f'Bearer {OBSERVER}:{TOKEN}'}
            if body is not None: headers['Content-Type'] = 'application/json'; body = json.dumps(body).encode()
            connection = http.client.HTTPConnection('127.0.0.1', backend_port, timeout=5)
            try:
                connection.request(method, path, body, headers)
                response = connection.getresponse(); data = response.read()
                assert response.status == 200, ('metadata status', response.status)
                return json.loads(data)
            finally: connection.close()

        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *args): pass

            def forward(self):
                self.connection.settimeout(35)
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= 10 * 1024 * 1024 + 4096:
                    self.send_error(413); return
                body = self.rfile.read(length) if length else None
                connection = http.client.HTTPConnection('127.0.0.1', backend_port, timeout=32)
                try:
                    headers = {key: value for key, value in self.headers.items() if key.lower() not in {'host', 'connection'}}
                    connection.request(self.command, self.path, body, headers)
                    response = connection.getresponse(); data = response.read()
                    entry = {'method': self.command, 'path': self.path, 'status': response.status}
                    if self.path == '/api/ack' and body:
                        ack = json.loads(body); entry.update(revision=ack['revision'], result=ack['result'])
                    if self.command == 'PUT' and response.status == 200:
                        commit = json.loads(data); entry.update(revision=commit['revision'], duplicate=commit.get('duplicate', False))
                    report['requests'].append(entry)
                    if self.command == 'PUT' and dropping.is_set():
                        dropping.clear(); report['faults'].append('committed PUT response dropped')
                        self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
                    self.send_response(response.status)
                    for name, value in response.getheaders():
                        if name.lower() not in {'connection', 'transfer-encoding', 'server', 'date', 'content-length'}:
                            self.send_header(name, value)
                    self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
                finally: connection.close()

            do_GET = do_POST = do_PUT = do_DELETE = forward

        def instrument(method, log, asynchronous=False):
            command = [adb, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'disposableFixture', '1',
                       '-e', 'server', f'https://127.0.0.1:{DEVICE_PORT}/', '-e', 'class', TEST_CLASS + '#' + method,
                       APP + '.test/androidx.test.runner.AndroidJUnitRunner']
            if asynchronous:
                return subprocess.Popen(command, env=environment, stdout=log, stderr=subprocess.STDOUT)
            run(command, stdout=log, stderr=subprocess.STDOUT)

        def phase(method):
            path = output / (method + '.txt')
            with path.open('w') as log: instrument(method, log)
            text = path.read_text()
            assert 'OK (1 test)' in text and 'INSTRUMENTATION_CODE: -1' in text, method
            report['completed'].append(method); print('PASS:', method, flush=True)

        def crash_phase(method, marker):
            nonlocal crash_process
            with (output / (method + '.txt')).open('w') as log:
                crash_process = instrument(method, log, asynchronous=True)
                for _ in range(150):
                    if crash_process.poll() is not None: raise RuntimeError('crash setup exited before force-stop: ' + method)
                    ready = device('shell', 'run-as', APP, 'ls', 'no_backup/disposable-service-e2e/' + marker, check=False)
                    if ready.returncode == 0: break
                    time.sleep(.2)
                else: raise RuntimeError('crash preparation timeout: ' + method)
                assert device('shell', 'pidof', APP).stdout.strip(), 'no live app to kill'
                device('shell', 'am', 'force-stop', APP)
                crash_process.wait(timeout=15)
                assert not device('shell', 'pidof', APP, check=False).stdout.strip(), 'process survived force-stop'
                crash_process = None
            report['faults'].append('force-stopped live process after ' + marker)
            print('PASS: abrupt process death after', marker, flush=True)

        def reverse(): device('reverse', f'tcp:{DEVICE_PORT}', f'tcp:{proxy.server_port}')

        def wait_boot():
            for _ in range(120):
                if device('shell', 'getprop', 'sys.boot_completed', check=False).stdout.strip() == '1': return
                time.sleep(2)
            raise RuntimeError('emulator did not finish booting')

        try:
            # Refuse an existing device; this test owns an entirely new emulator and AVD.
            connected = subprocess.check_output([adb, 'devices'], env=environment).decode().splitlines()[1:]
            assert not any(line.strip() for line in connected), 'existing Android device detected'
            if not (sdk / 'system-images/android-31/default/x86_64').exists():
                run([str(sdk / 'cmdline-tools/latest/bin/sdkmanager'), 'system-images;android-31;default;x86_64'], stdin=subprocess.DEVNULL)
            run([str(sdk / 'cmdline-tools/latest/bin/avdmanager'), 'create', 'avd', '--force', '--name', 'rcs-service-e2e',
                 '--package', 'system-images;android-31;default;x86_64', '--device', 'pixel_5'], input='no\n', text=True)
            config = Path(environment['ANDROID_AVD_HOME']) / 'rcs-service-e2e.avd/config.ini'
            config.write_text(config.read_text() + '\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\nhw.ramSize=2048\n')
            run([*android, 'emulator', 'start', '--cold', '--headless', 'rcs-service-e2e'])
            wait_boot()
            assert device('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() == '1'
            report['android_api'] = device('shell', 'getprop', 'ro.build.version.sdk').stdout.strip()
            report['page_size'] = device('shell', 'getconf', 'PAGESIZE').stdout.strip()
            for apk in apks: device('install', '-r', str(apk), timeout=90)
            smoke = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                           ','.join(NORMAL + '.' + name for name in ['BindingSmokeTest', 'MainThreadGateTest', 'SecureStateTest']),
                           NORMAL + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=120).stdout
            (output / 'production-smoke.txt').write_text(smoke)
            assert 'OK (6 tests)' in smoke and 'INSTRUMENTATION_CODE: -1' in smoke
            run([*android, 'run', '--apks', str(apks[0]), '--activity', NORMAL + '.MainActivity', '--device', SERIAL])
            with (output / 'empty-ui-layout.json').open('w') as layout:
                run([*android, 'layout', '--device', SERIAL], stdout=layout)
            assert 'RustClipSync' in (output / 'empty-ui-layout.json').read_text()
            device('shell', 'am', 'force-stop', NORMAL)
            start_backend()
            metadata('/api/devices', basic=True, method='POST', body={'deviceId': OBSERVER, 'deviceToken': TOKEN})
            cert, key = temporary / 'cert.pem', temporary / 'key.pem'
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost',
                            '-keyout', str(key), '-out', str(cert)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            proxy = ThreadingHTTPServer(('127.0.0.1', 0), Proxy); proxy.daemon_threads = True
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, key)
            proxy.socket = tls.wrap_socket(proxy.socket, server_side=True)
            proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True); proxy_thread.start()
            reverse()
            phase('sendReceiveAndSensitiveInbox')
            baseline = metadata(); assert baseline['revision'] == 3
            identities = {item['deviceId'] for item in metadata('/api/devices', basic=True)}
            assert len(identities) == 3
            device('reverse', '--remove', f'tcp:{DEVICE_PORT}')
            crash_phase('persistOfflineQueueUntilKilled', 'offline-ready')
            assert metadata()['revision'] == 3, 'offline data reached server'
            boot_id = device('shell', 'cat', '/proc/sys/kernel/random/boot_id').stdout.strip()
            device('reboot'); time.sleep(2); wait_boot(); reverse()
            assert device('shell', 'cat', '/proc/sys/kernel/random/boot_id').stdout.strip() != boot_id
            report['faults'].append('Android device rebooted with saved inbox and offline transaction')
            phase('recoverAfterProcessAndDeviceRestart')
            assert metadata()['revision'] == 6
            assert {item['deviceId'] for item in metadata('/api/devices', basic=True)} == identities
            dropping.set()
            crash_phase('persistUncertainUploadUntilKilled', 'uncertain-ready')
            committed = metadata(); assert committed['revision'] == 7
            stop_backend(); start_backend()
            assert metadata()['epoch'] == baseline['epoch'] and metadata()['revision'] == 7
            report['faults'].append('real SQLCipher-backed server process restarted with same database')
            phase('recoverCommittedUploadAfterServerRestart')
            assert metadata()['revision'] == 7, 'uncertain upload created another revision'
            assert {item['deviceId'] for item in metadata('/api/devices', basic=True)} == identities
            assert any(item.get('revision') == 7 and item.get('duplicate') is True for item in report['requests'])
            assert {item.get('result') for item in report['requests'] if item['path'] == '/api/ack'} >= {'applied', 'skipped', 'self'}
            report['final_revision'] = 7
            report['status'] = 'passed'
            print('PASS: real server send/receive, three sensitive policies, offline latest-only, process/device/server restart, unknown-commit deduplication', flush=True)
        finally:
            if crash_process is not None and crash_process.poll() is None:
                device('shell', 'am', 'force-stop', APP, check=False)
                crash_process.wait(timeout=15)
            if proxy is not None: proxy.shutdown(); proxy.server_close()
            if proxy_thread is not None: proxy_thread.join(timeout=5)
            stop_backend(); backend_log.close()
            subprocess.run([*android, 'emulator', 'stop', SERIAL], env=environment, timeout=30, check=False)
            subprocess.run([adb, 'kill-server'], env=environment, timeout=10, check=False, stdout=subprocess.DEVNULL)
            shutil.rmtree(temporary)
            report['temporary_fixture_removed'] = True
            (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
