#!/usr/bin/env python3
"""Check the locked core's real HTTP routes against a disposable loopback TLS fixture."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
EPOCH = '10000000-0000-4000-8000-000000000001'
DEVICE = '20000000-0000-4000-8000-000000000001'
MESSAGE = '30000000-0000-4000-8000-000000000001'
BODY = b'synthetic route fixture'


def main():
    subprocess.run(['cargo', 'build', '--locked', '-p', 'rustclipsync-mobile-ffi',
                    '--example', 'core_api_smoke'], cwd=ROOT, check=True)
    metadata = json.loads(subprocess.check_output(
        ['cargo', 'metadata', '--locked', '--offline', '--no-deps', '--format-version', '1'], cwd=ROOT))
    probe = Path(metadata['target_directory']) / 'debug' / 'examples' / ('core_api_smoke.exe' if os.name == 'nt' else 'core_api_smoke')
    # Canned replies validate route selection, not a second implementation of the state machine.
    operations = [
        ('POST', 'devices', {'epoch': EPOCH, 'ackRevision': 0, 'currentRevision': 0}),
        ('GET', f'devices/{DEVICE}/sync-direction', {'deviceId': DEVICE, 'sessionId': None, 'version': 0,
                                                   'direction': None, 'paused': False, 'updatedAt': None,
                                                   'expiresAt': None, 'revoked': False}),
        ('PUT', f'devices/{DEVICE}/sync-direction', 'direction-ack'),
        ('GET', 'state', {'epoch': EPOCH, 'revision': 1, 'ackRevision': 0, 'messageId': MESSAGE,
                          'deliveryRevision': 1, 'sourceDeviceId': DEVICE, 'size': len(BODY), 'sensitive': False}),
        ('PUT', f'devices/{DEVICE}/sync-direction', 'direction-ack'),
        ('GET', f'changes?epoch={EPOCH}&after=0', None),
        ('GET', f'messages/{MESSAGE}/content', BODY),
        ('PUT', f'messages/{MESSAGE}', {'epoch': EPOCH, 'revision': 1, 'messageId': MESSAGE}),
        ('DELETE', f'messages/{MESSAGE}', {'state': 'cancelled'}),
        ('POST', 'ack', {'ackRevision': 1}),
        ('DELETE', f'devices/{DEVICE}/sync-direction', 'direction-ack'),
    ]
    report = []
    with tempfile.TemporaryDirectory(prefix='rcs-mobile-api-') as temporary:
        cert, key = Path(temporary) / 'cert.pem', Path(temporary) / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=localhost', '-keyout', str(key), '-out', str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        expected, observed, mismatches = [], [], []
        accepted = [None]

        class Fixture(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self):
                self.connection.settimeout(5)
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= 4096:
                    self.send_error(413)
                    return
                request_body = self.rfile.read(length)
                if self.path.endswith('/api/ws') and self.headers.get('Upgrade', '').lower() == 'websocket':
                    # This route fixture deliberately exercises the core's HTTPS fallback.
                    self.send_response(503)
                    self.send_header('X-RCS-Protocol', '3')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                actual = (self.command, self.path)
                index = len(observed)
                observed.append(actual)
                if index >= len(expected) or actual != expected[index][:2]:
                    mismatches.append(actual)
                    self.send_error(404)
                    return
                response = expected[index][2]
                if self.headers.get('X-RCS-Protocol') != '3':
                    mismatches.append(('protocol header', self.path))
                if response == 'direction-ack':
                    update = json.loads(request_body)
                    accepted[0] = update['sessionId']
                    now = int(time.time() * 1000)
                    response = {'deviceId': DEVICE, 'sessionId': update['sessionId'], 'version': update['version'],
                                'serverTime': now, 'expiresAt': None if self.command == 'DELETE' else now + 86400000,
                                'revoked': self.command == 'DELETE'}
                elif self.path.endswith(('/api/state', '/content', '/api/ack')) or '/api/changes?' in self.path or self.command == 'PUT':
                    if accepted[0] is None or self.headers.get('X-RCS-Sync-Session') != accepted[0]:
                        mismatches.append(('session header', self.path))
                body = response if isinstance(response, bytes) else json.dumps(response).encode()
                if response is None:
                    body = b''
                self.send_response(204 if response is None else 200)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Content-Type', 'text/plain' if isinstance(response, bytes) else 'application/json')
                self.send_header('X-RCS-Protocol', '3')
                self.end_headers()
                self.wfile.write(body)

            do_GET = do_POST = do_PUT = do_DELETE = respond

        server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        server.daemon_threads = True
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            environment = dict(os.environ, RCS_MOBILE_DISPOSABLE_TEST='1', NO_PROXY='127.0.0.1', no_proxy='127.0.0.1')
            for base_path in ['/', '/synthetic-mount']:
                prefix = base_path.rstrip('/')
                expected[:] = [(method, prefix + '/api/' + path, response) for method, path, response in operations]
                observed.clear()
                mismatches.clear()
                accepted[0] = None
                result = subprocess.run([str(probe), f'https://127.0.0.1:{server.server_port}{base_path}'],
                                        env=environment, check=False, timeout=60)
                wanted = [(method, path) for method, path, _ in expected]
                if result.returncode or mismatches or observed != wanted:
                    raise AssertionError(f'Core HTTP routes differ: expected {wanted}, observed {observed}')
                report.extend(f'{method} {path}' for method, path in observed)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)
    revision = json.loads((ROOT / 'vendor/core-provenance.json').read_text())['revision']
    text = f'Core revision: {revision}\nPASS: 22 real HTTPS requests; protocol 3, direction ACK/lease refresh, WSS rejection/HTTP fallback, session headers, revocation, root and nested paths\n' + '\n'.join(report) + '\n'
    output = ROOT / 'build/core-api-routes.txt'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text)
    print(text, end='')


if __name__ == '__main__':
    main()
