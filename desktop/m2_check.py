"""M2 live faults, process termination and settings; no visible windows/Docker."""
import argparse
import json
import os
from pathlib import Path
import queue
import secrets
import shutil
import ssl
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit

from validate import Validation, ROOT, digest, need
from live_support import wait_for
from controlled_proxy import HttpProxy, SocksProxy
from network import DesktopError
import network
import engine as engine_module


class Worker:
    def __init__(self, root, base, token):
        self.process = subprocess.Popen([sys.executable, str(ROOT / 'tests/crash_worker.py')],
            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding='utf-8', env={**os.environ, 'PYTHONUTF8': '1'})
        self.responses = queue.Queue()
        def read():
            for line in self.process.stdout:
                self.responses.put(json.loads(line))
            self.responses.put({'error': 'WORKER_EXITED'})
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        self.send({'root': str(root), 'base': base, 'token': token})
        ready = self.receive()
        need(ready.get('ready'), 'WORKER_NOT_READY')
        self.pid = ready['pid']

    def send(self, value):
        self.process.stdin.write(json.dumps(value) + '\n')
        self.process.stdin.flush()

    def receive(self):
        try:
            result = self.responses.get(timeout=40)
        except queue.Empty:
            raise DesktopError('WORKER_TIMEOUT') from None
        if result.get('error'):
            raise DesktopError(result['error'])
        return result

    def call(self, operation, **values):
        self.send({'op': operation, **values})
        return self.receive().get('result')

    def close(self, kill=False):
        if self.process.poll() is None:
            if kill:
                if os.name == 'nt':
                    result = subprocess.run(['powershell.exe', '-NoProfile', '-Command',
                        f'Stop-Process -Id {self.pid} -Force -ErrorAction Stop'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                    need(result.returncode == 0, 'WORKER_TERMINATION_FAILED')
                else:
                    self.process.kill()
            else:
                self.send({'op': 'close'})
                self.receive()
            self.process.wait(timeout=40)
            if kill:
                need(self.process.returncode != 0, 'WORKER_NOT_FORCIBLY_TERMINATED')
        self.reader.join(timeout=2)


class M2Validation(Validation):
    def __init__(self, base):
        base = network.api_url(base)
        need(urlsplit(base).hostname in ('127.0.0.1', 'localhost'), 'M2_REQUIRES_LOOPBACK_BACKEND')
        super().__init__(base, False, native_only=True)
        self.workers = []

    def worker(self, user=None, base=None):
        item = Worker(self.out / 'process-native', base or self.api.base, (user or self.owner)['accessToken'])
        self.workers.append(item)
        return item

    def track(self, task):
        if task['direction'] != 'upload':
            return
        for node in task['nodes']:
            if node.get('uploadId'):
                value = (self.owner, '', node['uploadId'])
                if value not in self.api.sessions:
                    self.api.sessions.append(value)
        # Api.cleanup collects roots in these run-owned accounts, including
        # files committed immediately before process termination.

    def task(self, worker, identity):
        tasks = worker.call('queue')['tasks']
        value = next(t for t in tasks if t['id'] == identity)
        self.track(value)
        return value

    def remote_tree(self, identity, team=None):
        prefix = f'/teams/{team}' if team else ''
        value = self.api.call('GET', prefix + f'/files/{identity}', self.owner)
        if value['fileType'] == 'folder':
            children = []
            page = 1
            while True:
                result = self.api.call('GET', prefix + '/files', self.owner, params={'parentId': identity, 'page': page, 'size': 100})
                children.extend(self.remote_tree(item['id'], team) for item in result['records'])
                if page >= result.get('pages', 1):
                    break
                page += 1
            value['children'] = children
        return value

    def process_recovery(self):
        source = self.out / 'process-source'
        (source / '空目录').mkdir(parents=True)
        (source / '嵌套').mkdir()
        (source / '嵌套/中文.txt').write_text('M2 process recovery', encoding='utf-8')
        (source / 'zero.txt').touch()
        big = source / 'large.bin'
        with big.open('wb') as stream:
            for _ in range(64):
                stream.write(os.urandom(1024 * 1024))
        worker = self.worker()
        worker.call('settings', settings={'uploadLimitBytesPerSec': 4 * 1024 * 1024})
        task = worker.call('upload', paths=[str(source)], folder=True)
        wait_for(lambda: any(n['completedChunks'] >= 1 for n in self.task(worker, task['id'])['nodes']), 90)
        worker.close(kill=True)
        parsed = urlsplit(self.api.base)
        alternate_host = 'localhost' if parsed.hostname == '127.0.0.1' else '127.0.0.1'
        alternate = parsed._replace(netloc=alternate_host + (f':{parsed.port}' if parsed.port else '')).geturl()
        for user, base in ((self.viewer, self.api.base), (self.owner, alternate)):
            isolated = self.worker(user, base)
            need(not isolated.call('queue')['tasks'], 'QUEUE_SCOPE_LEAK')
            try:
                isolated.call('resume', id=task['id'])
            except DesktopError as error:
                need(str(error) == 'TASK_NOT_FOUND', 'WRONG_SCOPE_ERROR')
            else:
                raise DesktopError('CROSS_IDENTITY_RESUME')
            isolated.close()
        worker = self.worker()
        paused = self.task(worker, task['id'])
        need(paused['status'] == 'paused', 'CRASH_TASK_AUTO_RESUMED')
        worker.call('settings', settings={})
        worker.call('resume', id=task['id'])
        def uploaded():
            value = self.task(worker, task['id'])
            need(value['status'] != 'error', 'PROCESS_UPLOAD_FAILED')
            return value if value['status'] == 'done' else None
        finished = wait_for(uploaded, 150)
        tree = self.remote_tree(finished['nodes'][0]['remoteFileId'])
        for node in finished['nodes']:
            if node['kind'] == 'file':
                remote = self.api.call('GET', f'/files/{node["remoteFileId"]}', self.owner)
                need(remote['md5Hash'] == digest(node['localPath']), 'PROCESS_UPLOAD_MD5')
        target = self.out / 'process-download'
        worker.call('settings', settings={'downloadLimitBytesPerSec': 4 * 1024 * 1024})
        download = worker.call('download', target=str(target), tree=tree)
        wait_for(lambda: any(n['bytesDone'] >= 1024 * 1024 for n in self.task(worker, download['id'])['nodes']), 90)
        worker.close(kill=True)
        worker = self.worker()
        need(self.task(worker, download['id'])['status'] == 'paused', 'CRASH_DOWNLOAD_AUTO_RESUMED')
        worker.call('settings', settings={})
        worker.call('resume', id=download['id'])
        def downloaded():
            value = self.task(worker, download['id'])
            need(value['status'] != 'error', 'PROCESS_DOWNLOAD_FAILED')
            return value if value['status'] == 'done' else None
        wait_for(downloaded, 150)
        for path in source.rglob('*'):
            restored = target / path.relative_to(source)
            need(restored.is_dir() if path.is_dir() else restored.is_file() and digest(restored) == digest(path), 'PROCESS_DIRECTORY_MISMATCH')
        worker.close()
        for path in (self.out / 'process-native').glob('desktop.sqlite3*'):
            content = path.read_bytes()
            need(not any(str(secret).encode() in content for secret in self.secrets if secret), 'PROCESS_CREDENTIAL_PERSISTED')
        return {'sizeMiB': 64, 'uploadForcedExit': True, 'downloadForcedExit': True, 'nestedDirectoryMd5': True,
                'emptyAndZeroFiles': True, 'otherAccountDenied': True, 'otherServerDenied': True, 'manualResumeRequired': True}

    def proxy_and_faults(self):
        username, password = secrets.token_hex(8), secrets.token_urlsafe(20)
        self.secrets.extend([username, password])
        def track(path, response):
            if path.endswith('/upload/init') and response.status_code == 200:
                data = response.json().get('data', {})
                if data.get('sessionId'):
                    self.api.sessions.append((self.owner, '', data['sessionId']))
        proxy = HttpProxy(self.api.base, username, password, track)
        try:
            self.engine.save_settings({'proxyMode': 'manual', 'proxyUrl': proxy.url, 'proxyUsername': username, 'proxyPassword': password,
                                       'uploadLimitBytesPerSec': 1024 * 1024})
            source = self.out / 'fault.bin'
            source.write_bytes(os.urandom(1024 * 1024))
            proxy.faults.update(['chunk', 'complete'])
            started = time.monotonic()
            task = self.finish(self.engine.upload_files([source], self.request()))
            need(time.monotonic() - started >= 3.8, 'RETRY_TRAFFIC_NOT_RATE_LIMITED')
            remote = self.api.call('GET', f'/files/{task["nodes"][0]["remoteFileId"]}', self.owner)
            need(remote['md5Hash'] == digest(source), 'RETRIED_UPLOAD_MD5')
            counts = {suffix: sum(path.endswith('/' + suffix) for _, path in proxy.calls) for suffix in ('init', 'chunk', 'complete')}
            need(counts == {'init': 1, 'chunk': 2, 'complete': 2}, 'UNSAFE_RETRY_COUNTS')
            self.engine.save_settings({'proxyMode': 'manual', 'proxyUrl': proxy.url, 'proxyUsername': username, 'proxyPassword': 'invalid'})
            denied = self.engine.download(self.out / 'proxy-denied.bin', {**self.request(), 'fileId': remote['id'], 'fileName': 'proxy-denied.bin'})
            self.engine.futures[denied['id']].result(timeout=30)
            need(self.engine._find(denied['id'])[0]['nodes'][0]['error'] == 'HTTP_407', 'PROXY_AUTH_NOT_ENFORCED')
            self.engine.save_settings({'proxyMode': 'manual', 'proxyUrl': proxy.url, 'proxyUsername': username, 'proxyPassword': password})
            # A lost initialization response must not allocate another session.
            uncertain = self.out / 'uncertain.bin'
            uncertain.write_bytes(os.urandom(1000))
            proxy.faults.add('init')
            pending = self.engine.upload_files([uncertain], self.request())
            self.engine.futures[pending['id']].result(timeout=60)
            failed = self.engine._find(pending['id'])[0]
            need(failed['nodes'][0]['error'] == 'RESULT_UNCERTAIN', 'UNCERTAIN_CREATE_REPLAYED')
            try:
                self.engine.resume(pending['id'])
            except DesktopError as error:
                need(str(error) == 'RESULT_UNCERTAIN', 'UNCERTAIN_RESUME_ERROR')
            else:
                raise DesktopError('UNCERTAIN_RESUMED')
            before = len(proxy.calls)
            self.engine.save_settings({})
            self.finish(self.engine.download(self.out / 'direct.bin', {**self.request(), 'fileId': remote['id'], 'fileName': 'direct.bin'}))
            need(len(proxy.calls) == before, 'DISABLED_PROXY_STILL_USED')
            self.big_file, self.big_path = remote['id'], source
        finally:
            self.engine.save_settings({})
            proxy.close()
        # A disposable local CA validates HTTPS-proxy transport without editing
        # the Windows trust store or disabling certificate verification.
        openssl = shutil.which('openssl')
        if not openssl:
            candidate = Path('C:/Program Files/Git/usr/bin/openssl.exe')
            openssl = str(candidate) if candidate.is_file() else None
        need(openssl is not None, 'OPENSSL_REQUIRED_FOR_LOCAL_TLS_FIXTURE')
        certificate, key = self.out / 'proxy-test.crt', self.out / 'proxy-test.key'
        subprocess.run([openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '1', '-nodes',
            '-keyout', str(key), '-out', str(certificate), '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=20)
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.load_cert_chain(certificate, key)
        proxy = HttpProxy(self.api.base, username, password, ssl_context=server_context)
        original_options = engine_module.client_options
        def local_trust(settings, base):
            options = original_options(settings, base)
            if settings['proxyUrl'] == proxy.url:
                options['proxy'].ssl_context = ssl.create_default_context(cafile=str(certificate))
            return options
        try:
            engine_module.client_options = local_trust
            self.engine.save_settings({'proxyMode': 'manual', 'proxyUrl': proxy.url, 'proxyUsername': username, 'proxyPassword': password})
            self.finish(self.engine.download(self.out / 'https-proxy.bin', {**self.request(), 'fileId': self.big_file, 'fileName': 'https-proxy.bin'}))
            need(bool(proxy.calls) and digest(self.out / 'https-proxy.bin') == digest(self.big_path), 'HTTPS_PROXY_FAILED')
        finally:
            engine_module.client_options = original_options
            self.engine.save_settings({})
            proxy.close()
        # System-proxy discovery is replaced only in this test process. No OS edits.
        proxy = HttpProxy(self.api.base)
        original = network.getproxies, network.proxy_bypass
        try:
            network.getproxies = lambda: {'http': proxy.url}
            network.proxy_bypass = lambda _: False
            self.engine.save_settings({'proxyMode': 'system'})
            self.finish(self.engine.download(self.out / 'system.bin', {**self.request(), 'fileId': self.big_file, 'fileName': 'system.bin'}))
            need(bool(proxy.calls), 'SYSTEM_PROXY_NOT_USED')
        finally:
            network.getproxies, network.proxy_bypass = original
            self.engine.save_settings({})
            proxy.close()
        destination = urlsplit(self.api.base)
        proxy = SocksProxy(destination.hostname, destination.port or 80, username, password)
        try:
            self.engine.save_settings({'proxyMode': 'manual', 'proxyUrl': proxy.url, 'proxyUsername': username, 'proxyPassword': password})
            self.finish(self.engine.download(self.out / 'socks.bin', {**self.request(), 'fileId': self.big_file, 'fileName': 'socks.bin'}))
            need(proxy.connections > 0, 'SOCKS_PROXY_NOT_USED')
            need(digest(self.out / 'socks.bin') == digest(self.big_path), 'SOCKS_DOWNLOAD_MD5')
        finally:
            self.engine.save_settings({})
            proxy.close()
        return {'authenticatedHttpProxy': True, 'authenticatedHttpsProxy': True, 'socks5Authentication': True, 'disabledAndSystemProxy': True,
                'lostChunkResponse': True, 'lostCompleteResponse': True, 'retryTrafficLimited': True,
                'proxyAuthFailureDenied': True, 'noDuplicateInit': True, 'uncertainInitBlocked': True}

    def rate_settings(self):
        sources = []
        for index in range(2):
            path = self.out / f'rate-{index}.bin'
            path.write_bytes(os.urandom(1024 * 1024))
            sources.append(path)
        self.engine.save_settings({'uploadLimitBytesPerSec': 1024 * 1024, 'maxConcurrentUploads': 2})
        start = time.monotonic()
        uploaded = self.finish(self.engine.upload_files(sources, self.request()))
        upload_seconds = time.monotonic() - start
        need(upload_seconds >= 1.8, 'UPLOAD_AGGREGATE_RATE_EXCEEDED')
        self.engine.save_settings({'downloadLimitBytesPerSec': 1024 * 1024, 'maxConcurrentDownloads': 2})
        start = time.monotonic()
        downloads = []
        for index, node in enumerate(n for n in uploaded['nodes'] if n['kind'] == 'file'):
            downloads.append(self.engine.download(self.out / f'rate-down-{index}.bin', {**self.request(), 'fileId': node['remoteFileId'], 'fileName': node['name']}))
        for task in downloads:
            self.finish(task)
        download_seconds = time.monotonic() - start
        need(download_seconds >= 1.8, 'DOWNLOAD_AGGREGATE_RATE_EXCEEDED')
        self.engine.save_settings({})
        return {'bytesPerDirection': 2 * 1024 * 1024, 'limitBytesPerSec': 1024 * 1024,
                'uploadSeconds': round(upload_seconds, 2), 'downloadSeconds': round(download_seconds, 2)}

    def cleanup(self):
        if self.process:
            self.close_app()
        for worker in self.workers:
            worker.close(kill=worker.process.poll() is None)
        super().cleanup()
        # Keep only the non-sensitive report, not the synthetic payloads/state.
        for child in list(self.out.iterdir()):
            need(not child.is_symlink() and not child.is_junction() and child.resolve().is_relative_to(self.out.resolve()), 'UNSAFE_CLEANUP_PATH')
            if child.is_dir():
                shutil.rmtree(child)
            elif child.name != 'results.json':
                child.unlink()

    def qt_settings(self):
        self.offscreen = True
        self.launch()
        self.login()
        target = self.out / 'settings-downloads'
        self.page.invoke('save_desktop_settings', {'settings': {'defaultDownloadDir': str(target)}})
        self.page.click('button[aria-controls="desktop-client-settings"]')
        self.page.wait("document.querySelectorAll('.desktop-settings-panel input[type=number]').length===4")
        self.page.evaluate("(() => {const values=['2.5','3','2','1'];document.querySelectorAll('.desktop-settings-panel input[type=number]').forEach((e,i)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,values[i]);e.dispatchEvent(new Event('input',{bubbles:true}));});document.querySelectorAll('.proxy-mode__item')[2].click();return true})()")
        self.page.click('.desktop-settings-panel__toolbar button')
        expected = {'uploadLimitBytesPerSec': 2621440, 'downloadLimitBytesPerSec': 3145728,
                    'maxConcurrentUploads': 2, 'maxConcurrentDownloads': 1, 'proxyMode': 'disabled', 'defaultDownloadDir': str(target)}
        def saved():
            current = self.page.invoke('get_desktop_settings')
            return all(current[key] == value for key, value in expected.items())
        wait_for(saved, 10)
        result = self.page.invoke('start_desktop_download_file', {'request': {
            'fileId': self.big_file, 'fileName': 'settings-target.bin', 'apiBaseUrl': self.api.base}})
        need(result['path'] == str(target / 'settings-target.bin'), 'DEFAULT_DIRECTORY_NOT_APPLIED')
        wait_for(lambda: (target / 'settings-target.bin').is_file(), 30)
        need(digest(target / 'settings-target.bin') == digest(self.big_path), 'SETTINGS_DOWNLOAD_MD5')
        need((self.out / 'ui-downloads/from-ui.bin').is_file(), 'EXISTING_DESTINATION_CHANGED')
        self.close_app()
        self.launch(expect_login=False)
        need(saved(), 'SETTINGS_NOT_PERSISTED')
        current = self.page.invoke('get_desktop_settings')
        need(not current['proxyUsername'] and not current['proxyPassword'], 'PROXY_CREDENTIALS_RESTORED')
        auth = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
        self.page.evaluate(auth + '.logout()')
        self.close_app()
        return {'realQtVueOffscreen': True, 'settingsUiSaved': True, 'defaultDirectoryUsed': True,
                'existingDestinationPreserved': True, 'applicationRestart': True, 'credentialsNotRestored': True}

    def run(self):
        failed = False
        try:
            for name, operation in [('isolated-backend-fixtures', self.setup), ('forced-process-recovery', self.process_recovery),
                    ('proxy-routing-and-response-loss', self.proxy_and_faults), ('aggregate-rate-settings', self.rate_settings),
                    ('personal-team-directories', self.directories), ('permission-revocation', self.revoked_permission),
                    ('native-auth-refresh', self.native_refresh), ('qt-settings-and-restart', self.qt_settings)]:
                self.step(name, operation)
        except Exception:
            failed = True
        finally:
            try:
                self.step('run-scoped-cleanup', self.cleanup)
            except Exception:
                failed = True
            report = json.dumps({'runId': self.id, 'checks': self.checks}, indent=2)
            need(not any(value and len(str(value)) > 8 and str(value) in report for value in self.secrets), 'REPORT_PRIVACY_CHECK_FAILED')
            (self.out / 'results.json').write_text(report, encoding='utf-8')
            print('report', self.out / 'results.json', flush=True)
        return int(failed)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--api-base', default='http://127.0.0.1:8080/api/v1')
    args = parser.parse_args()
    sys.exit(M2Validation(args.api_base).run())
