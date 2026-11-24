"""Real M5 state recovery. Credentials remain in this process or anonymous pipes."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from m4_support import owned_root, check_ports, require

AUTH = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
ROUTER = "document.querySelector('#app').__vue_app__.config.globalProperties.$router"


def corrupt_probe(config, root):
    """Run the actual app entrypoint; capture its error dialog without a window."""
    import hashlib
    import runpy
    from PySide6.QtWidgets import QMessageBox
    profile = root / 'work/corrupt/profile'
    (profile / 'native').mkdir(parents=True)
    (profile / '.bcd-qt-desktop').touch()
    database = profile / 'native/desktop.sqlite3'
    database.write_bytes(b'M5 intentionally invalid SQLite fixture')
    before = hashlib.sha256(database.read_bytes()).hexdigest()
    shown = []
    QMessageBox.critical = lambda parent, title, message: shown.append('local state is damaged' in message)
    sys.argv = [str(ROOT / 'app.py'), '--profile', str(profile), '--web-root', str(root / 'work/web'),
                '--api-base', config['apiBase'], '--port', str(config['port'])]
    code = None
    try:
        runpy.run_path(str(ROOT / 'app.py'), run_name='__main__')
    except SystemExit as error:
        code = error.code
    require(code == 1 and shown == [True], 'CORRUPT_STATE_ERROR_NOT_SHOWN')
    require(hashlib.sha256(database.read_bytes()).hexdigest() == before, 'CORRUPT_STATE_OVERWRITTEN')
    check_ports(config['port'], config['debugPort'])
    print(json.dumps({'exitCode': code, 'errorDialogCaptured': True, 'originalBytesPreserved': True, 'portsReleased': True}))


def live(config, root):
    from m4_live import validation_type
    from m2_check import Worker
    from engine import DEFAULTS
    from live_support import wait_for
    from validate import digest, need
    from PySide6.QtCore import QLockFile

    class M5Validation(validation_type(config, root)):
        def __init__(self):
            super().__init__()
            self.starts = []
            self.metrics = {'startupSeconds': self.starts}
            self.workers = []
            self.original_out = self.out
            self.expected_settings = None
            self.required = ['empty-profiles', 'vue-settings-restart', 'exclusive-upload-seed',
                             'qt-upload-close-resume-md5', 'team-directory-fixture',
                             'qt-download-close-isolation-resume-md5', 'native-state-privacy', 'corrupt-sqlite-startup']

        def queue(self):
            return self.page.invoke('get_transfer_queue')['tasks']

        def launch(self, expect_login=True):
            # A multipart upload has not published a file yet. Restored login
            # must accept an authenticated empty file list, not wait for a card.
            ready = None if expect_login else "!!document.querySelector('#app')?.__vue_app__ && " + AUTH + '.isLoggedIn && ' + AUTH + '.initialized && ' + ROUTER + ".currentRoute.value.path==='/files'"
            return super().launch(expect_login, ready_expression=ready)

        def task(self, identity):
            return next(t for t in self.queue() if t['id'] == identity)

        def login_as(self, user, base=None):
            self.page.evaluate(ROUTER + '.isReady()')
            self.page.evaluate(AUTH + '.login(' + json.dumps(user['username']) + ',' + json.dumps(user['password']) + ')')
            self.page.evaluate(ROUTER + ".push('/files')")
            self.page.wait(ROUTER + ".currentRoute.value.path==='/files'")
            need(self.page.invoke('desktop:diagnostics')['authenticated'], 'NATIVE_IDENTITY_MISSING')
            for key in ('accessToken', 'refreshToken'):
                user[key] = self.page.evaluate('localStorage.getItem(' + json.dumps(key) + ')')
                self.secrets.append(user[key])

        def logout(self):
            self.page.evaluate(AUTH + '.logout()')
            self.page.wait("!!document.querySelector('.login-page__form')")
            need(not self.queue(), 'QUEUE_VISIBLE_AFTER_LOGOUT')
            need(not self.page.invoke('desktop:diagnostics')['authenticated'], 'IDENTITY_RETAINED_AFTER_LOGOUT')

        def set_server(self, value):
            self.page.evaluate("document.querySelector('button[aria-controls=desktop-connection-settings]').click();true")
            self.page.wait("!!document.querySelector('#desktop-connection-settings input')")
            self.page.evaluate("(() => {const e=document.querySelector('#desktop-connection-settings input');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e," + json.dumps(value) + ");e.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#desktop-connection-settings').requestSubmit();return true})()")
            self.page.wait("!document.querySelector('#desktop-connection-settings')")
            need(self.page.evaluate("localStorage.getItem('bcd.apiBaseUrl')") == value, 'SERVER_NOT_SAVED')
            need(not self.page.evaluate("localStorage.getItem('accessToken')||localStorage.getItem('refreshToken')"), 'SERVER_RESTORED_IDENTITY')

        def empty_profiles(self):
            for label in ('primary', 'secondary'):
                if label == 'secondary':
                    self.out = root / 'work/second-runtime'; self.out.mkdir()
                self.launch()
                self.security()
                need(not self.page.invoke('desktop:diagnostics')['authenticated'] and not self.queue(), 'NEW_PROFILE_NOT_EMPTY')
                need(self.page.invoke('get_desktop_settings') == DEFAULTS, 'NEW_PROFILE_SETTINGS_NOT_DEFAULT')
                need(not self.page.evaluate("localStorage.getItem('accessToken')||localStorage.getItem('refreshToken')"), 'NEW_PROFILE_HAS_TOKEN')
                self.close_app()
            self.out = self.original_out
            return {'twoIndependentProfiles': True, 'loginPage': True, 'defaultSettings': True, 'emptyQueueAndIdentity': True, 'runtimeBootstrapAndSubframeIsolation': True}

        def settings_restart(self):
            self.launch(); self.login_as(self.owner)
            self.downloads_dir = self.out / 'downloads'
            # Directory selection has already been tested natively in M1/M2.
            # Seed the existing settings command, then verify the real Vue save
            # preserves that path together with the edited fields.
            self.save_settings({'defaultDownloadDir': str(self.downloads_dir)})
            self.page.evaluate("document.querySelector('button[aria-controls=desktop-client-settings]').click();true")
            self.page.wait("document.querySelectorAll('.desktop-settings-panel input[type=number]').length===4")
            self.page.evaluate("(() => {const values=['0.5','0.75','2','2'];document.querySelectorAll('.desktop-settings-panel input[type=number]').forEach((e,i)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,values[i]);e.dispatchEvent(new Event('input',{bubbles:true}));});document.querySelectorAll('.proxy-mode__item')[2].click();document.querySelector('.desktop-settings-panel__toolbar button').click();return true})()")
            expected = {**DEFAULTS, 'defaultDownloadDir': str(self.downloads_dir), 'uploadLimitBytesPerSec': 524288,
                        'downloadLimitBytesPerSec': 786432, 'maxConcurrentUploads': 2, 'maxConcurrentDownloads': 2, 'proxyMode': 'disabled'}
            wait_for(lambda: self.page.invoke('get_desktop_settings') == expected, 10)
            proxy_user, proxy_password = 'm5_' + secrets.token_hex(12), secrets.token_hex(24)
            self.secrets.extend([proxy_user, proxy_password])
            self.save_settings({'proxyUsername': proxy_user, 'proxyPassword': proxy_password})
            self.logout()
            target = self.api.base.replace('127.0.0.1', 'localhost') if '127.0.0.1' in self.api.base else self.api.base.replace('localhost', '127.0.0.1')
            self.set_server(target); self.close_app(); self.launch()
            need(self.page.evaluate("localStorage.getItem('bcd.apiBaseUrl')") == target, 'SERVER_NOT_PERSISTED')
            need(self.page.invoke('get_desktop_settings') == expected, 'SETTINGS_NOT_PERSISTED')
            self.set_server(self.api.base); self.close_app()
            self.expected_settings = expected
            return {'realVueSave': True, 'directorySeededByExistingSettingsCommand': True, 'directoryRetainedByVueSave': True,
                    'concurrencyAndLimitsPersisted': True, 'serverPersisted': True, 'proxyCredentialsNotRestored': True}

        def seed_upload(self):
            self.big_path = self.out / '个人八兆.bin'; self.big_path.write_bytes(os.urandom(8*1024*1024))
            self.owner.update(self.api.call('POST', '/auth/login', json={'username': self.owner['username'], 'password': self.owner['password']}))
            self.secrets.extend(self.owner[k] for k in ('accessToken', 'refreshToken'))
            lock = QLockFile(str(self.out / 'profile/instance.lock'))
            need(lock.tryLock(0), 'SEED_PROFILE_NOT_EXCLUSIVE')
            worker = None
            try:
                worker = Worker(self.out / 'profile/native', self.api.base, self.owner['accessToken']); self.workers.append(worker)
                worker.call('settings', settings=self.expected_settings)
                created = worker.call('upload', paths=[str(self.big_path)])
                self.upload_id = created['id']
                def initialized():
                    task = next(t for t in worker.call('queue')['tasks'] if t['id'] == self.upload_id)
                    need(task['status'] != 'error', 'UPLOAD_SEED_FAILED')
                    return task if task['nodes'][0]['uploadId'] else None
                task = wait_for(initialized, 30, interval=.1)
                self.upload_session = task['nodes'][0]['uploadId']
                self.api.sessions.append((self.owner, '', self.upload_session))
            finally:
                if worker: worker.close()
                lock.unlock()
            need(worker.process.returncode == 0, 'UPLOAD_SEED_PROCESS_FAILED')
            return {'engineProcessExited': True, 'profileExclusivelyLocked': True, 'sessionCreatedByRealBackend': True, 'sizeMiB': 8}

        def wait_task(self, identity):
            def finished():
                task = self.task(identity)
                need(task['status'] != 'error', 'TRANSFER_FAILED')
                return task if task['status'] in ('done','instant') else None
            return wait_for(finished, 90, interval=.2)

        def resume(self, identity):
            return self.page.invoke('resume_transfer_node', {'nodeId': identity, 'apiBaseUrl': self.api.base})

        def upload_resume(self):
            print('M5 upload: login-seeded-profile', flush=True)
            self.launch(); self.login_as(self.owner)
            need(self.task(self.upload_id)['status'] == 'paused', 'UPLOAD_NOT_PAUSED_ON_START')
            print('M5 upload: resume-first-chunk', flush=True)
            self.resume(self.upload_id)
            def progressed():
                task = self.task(self.upload_id)
                need(task['status'] != 'error', 'UPLOAD_RESUME_FAILED')
                return task if task['nodes'][0]['completedChunks'] >= 1 and task['status'] == 'transferring' else None
            wait_for(progressed, 60, interval=.1)
            print('M5 upload: normal-close-after-first-chunk', flush=True)
            self.close_app(); self.launch(expect_login=False)
            need(self.task(self.upload_id)['status'] == 'paused', 'UPLOAD_AUTOMATICALLY_RESUMED')
            self.logout(); self.login_as(self.owner)
            task = self.task(self.upload_id)
            need(task['status'] == 'paused' and task['nodes'][0]['uploadId'] == self.upload_session, 'UPLOAD_IDENTITY_CHANGED')
            self.save_settings({'uploadLimitBytesPerSec': None})
            print('M5 upload: resume-same-session', flush=True)
            self.resume(self.upload_id); done = self.wait_task(self.upload_id)
            need(done['nodes'][0]['uploadId'] == self.upload_session, 'UPLOAD_SESSION_REPLACED')
            self.big_file = done['nodes'][0]['remoteFileId']; self.api.files.append((self.owner, None, self.big_file))
            result = self.page.invoke('start_desktop_download_file', {'request': {**self.request(), 'fileId': self.big_file, 'fileName': '个人校验.bin'}})
            self.save_settings({'downloadLimitBytesPerSec': None})
            path, _ = self.wait_download('个人校验.bin')
            need(digest(path) == digest(self.big_path) and result['path'] == str(path), 'UPLOAD_MD5_FAILED')
            return {'normalWindowCloseDuringUpload': True, 'pausedAfterRestartAndReauthentication': True,
                    'sameTaskAndUploadSession': True, 'nativeBridgeDownloadMd5': True}

        def team_fixture(self):
            self.folder_path = self.out / '团队原目录'
            (self.folder_path / '空目录').mkdir(parents=True)
            (self.folder_path / '嵌套').mkdir()
            (self.folder_path / '嵌套/中文.txt').write_text('M5 '+self.id, encoding='utf-8')
            (self.folder_path / '零字节.txt').touch()
            (self.folder_path / '八兆.bin').write_bytes(os.urandom(8*1024*1024))
            self.engine.clear_session(); self.engine.authenticate(self.owner['accessToken'], self.api.base)
            self.finish(self.engine.upload_files([self.folder_path], self.request(self.team), folder=True))
            self.folder_id = self.engine.tasks[-1]['nodes'][0]['remoteFileId']
            self.current_team = self.team
            return {'teamDirectoryRealEngineUpload': True, 'sizeMiB': 8, 'unicodeZeroNestedEmpty': True}

        def denied_resume(self, identity, base):
            payload = json.dumps({'command': 'resume_transfer_node', 'args': {'nodeId': identity, 'apiBaseUrl': base}})
            response = self.page.evaluate("new Promise((resolve,reject)=>{const r=" + payload + ";r.id=crypto.randomUUID();const timer=setTimeout(()=>reject(new Error('TIMEOUT')),10000);const done=e=>{const v=JSON.parse(e.detail);if(v.id!==r.id)return;clearTimeout(timer);window.removeEventListener('bcd:response',done);resolve(v)};window.addEventListener('bcd:response',done);window.dispatchEvent(new CustomEvent('bcd:request',{detail:JSON.stringify(r)}));})")
            need(response.get('error') == 'TASK_NOT_FOUND', 'WRONG_IDENTITY_RESUME_NOT_DENIED')

        def download_isolation(self):
            self.save_settings({'downloadLimitBytesPerSec': 524288})
            tree = self.tree(self.folder_id); tree['fileName'] = '团队恢复'
            result = self.page.invoke('start_desktop_download_folder', {'request': {**self.request(self.team), 'root': tree}})
            def progressed():
                return next((t for t in self.queue() if t['name'] == tree['fileName'] and t['status'] == 'transferring' and t['bytesDone'] >= 1024*1024), None)
            pending = wait_for(progressed, 30, interval=.1); identity = pending['id']
            self.close_app(); self.launch(expect_login=False)
            need(self.task(identity)['status'] == 'paused', 'DOWNLOAD_AUTOMATICALLY_RESUMED')
            self.logout(); self.login_as(self.viewer)
            need(not self.queue(), 'OTHER_ACCOUNT_QUEUE_VISIBLE'); self.denied_resume(identity, self.api.base)
            self.logout(); self.login_as(self.owner)
            need(self.task(identity)['status'] == 'paused', 'ACCOUNT_SWITCH_STARTED_DOWNLOAD')
            self.logout()
            target = self.api.base.replace('127.0.0.1','localhost') if '127.0.0.1' in self.api.base else self.api.base.replace('localhost','127.0.0.1')
            self.set_server(target); self.login_as(self.owner)
            need(not self.queue(), 'OTHER_SERVER_QUEUE_VISIBLE'); self.denied_resume(identity, target)
            self.logout(); self.set_server(self.api.base); self.login_as(self.owner)
            need(self.task(identity)['status'] == 'paused', 'SERVER_SWITCH_STARTED_DOWNLOAD')
            self.save_settings({'downloadLimitBytesPerSec': None}); self.resume(identity)
            destination, finished = self.wait_download(tree['fileName'])
            expected = {str(p.relative_to(self.folder_path)): digest(p) for p in self.folder_path.rglob('*') if p.is_file()}
            actual = {str(p.relative_to(destination)): digest(p) for p in destination.rglob('*') if p.is_file()}
            need(finished['id'] == identity and expected == actual and (destination/'空目录').is_dir(), 'DIRECTORY_RESUME_MD5_FAILED')
            need(result['path'] == str(destination), 'DIRECTORY_TARGET_CHANGED')
            self.logout(); self.close_app()
            return {'normalWindowCloseDuringDownload': True, 'pausedAfterRestart': True, 'accountAndServerResumeDenied': True,
                    'originalTaskManuallyResumed': True, 'allMd5': True, 'emptyDirectory': True}

        def native_privacy(self):
            for directory in (self.out/'native', self.out/'profile/native', root/'work/second-runtime/profile/native'):
                for path in directory.glob('*'):
                    if path.is_file():
                        data = path.read_bytes()
                        need(not any(str(value).encode() in data for value in self.secrets if value and len(str(value)) > 8), 'NATIVE_STATE_CONTAINS_CREDENTIAL')
            return {'nativeSqliteAndWalScanned': True, 'noTokensOrProxyCredentials': True, 'webSessionStorageExcludedAsDesigned': True}

        def corrupt(self):
            value = {**config, 'corruptProbe': True}
            process = subprocess.run([sys.executable, str(Path(__file__))], input=json.dumps(value)+'\n', cwd=ROOT,
                env={**self.process_env, 'QT_QPA_PLATFORM': 'offscreen'}, text=True, encoding='utf-8',
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
            need(process.returncode == 0, 'CORRUPT_PROBE_FAILED')
            return json.loads(process.stdout.strip().splitlines()[-1])

        def run(self):
            failed = False
            try:
                self.step('isolated-backend-fixtures', self.setup)
                for label, operation in zip(self.required, (self.empty_profiles, self.settings_restart, self.seed_upload,
                    self.upload_resume, self.team_fixture, self.download_isolation, self.native_privacy, self.corrupt)):
                    self.step(label, operation)
            except (Exception, KeyboardInterrupt) as error:
                import re, traceback
                failed = True
                code = str(error) if re.fullmatch('[A-Z_0-9]{1,80}', str(error)) else type(error).__name__
                self.checks.append({'name': 'execution', 'status': 'failed', 'code': code,
                                    'frames': [{'file': Path(f.filename).name, 'function': f.name, 'line': f.lineno}
                                               for f in traceback.extract_tb(error.__traceback__)]})
            finally:
                for worker in self.workers:
                    try: worker.close()
                    except Exception: failed = True
                if self.process:
                    try: self.step('failure-process-release', self.close_app)
                    except Exception: failed = True
                self.out = self.original_out
                try: self.step('run-scoped-backend-profile-cleanup', self.cleanup)
                except Exception: failed = True
                for path in self.out.glob('*.png'):
                    path.replace(root/path.name)
                complete = all(any(c['name'] == n and c['status'] == 'passed' for c in self.checks) for n in self.required)
                failed |= not complete
                self.metrics['rssMiBPeak'] = round(self.rss_peak, 2)
                report = {'runId': self.id, 'status': 'failed' if failed else 'passed', 'requiredComplete': complete and not failed,
                          'checks': self.checks, 'metrics': self.metrics, 'manualObservation': 'not-executed',
                          'visibleMouseKeyboardAutomation': False,
                          'notExecuted': [n for n in self.required if not any(c['name'] == n for c in self.checks)]}
                text = json.dumps(report, indent=2)
                need(not any(str(v) in text for v in self.secrets if v and len(str(v)) > 8), 'REPORT_PRIVACY_CHECK_FAILED')
                (root/'live.json').write_text(text, encoding='utf-8')
            return int(failed)

    return M5Validation().run()


def main():
    config = json.loads(sys.stdin.readline()); root = owned_root(config['root'])
    check_ports(config['port'], config['debugPort'])
    if config.get('corruptProbe'):
        corrupt_probe(config, root)
        return 0
    return live(config, root)


if __name__ == '__main__':
    sys.exit(main())
