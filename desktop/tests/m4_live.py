"""M4 real Qt/Vue acceptance, invoked only by the stdlib bootstrap."""
import json
import os
import re
import shutil
from pathlib import Path
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from m4_support import owned_root, require, remove_owned, check_ports, process_fingerprints, surviving_owned


def probe(root):
    import platform
    import PySide6
    from PySide6.QtCore import QLibraryInfo, qVersion
    from PySide6 import QtWebEngineCore, QtMultimedia
    work = root / 'work'
    require(Path(sys.prefix).resolve() == (work / 'env').resolve(), 'WRONG_VIRTUAL_ENVIRONMENT')
    require(Path(sys.base_prefix).resolve().is_relative_to((work / 'python').resolve()), 'SYSTEM_PYTHON_USED')
    require(platform.python_version() == '3.12.13' and PySide6.__version__ == '6.12.0', 'RUNTIME_VERSION_MISMATCH')
    plugins = Path(QLibraryInfo.path(QLibraryInfo.LibraryPath.PluginsPath)).resolve()
    require(plugins.is_relative_to(Path(sys.prefix).resolve()), 'EXTERNAL_QT_PLUGINS')
    result = {'python': platform.python_version(), 'pyside': PySide6.__version__, 'qt': qVersion(),
              'webengineImported': bool(QtWebEngineCore.QWebEnginePage), 'multimediaImported': bool(QtMultimedia.QMediaPlayer),
              'freshInterpreter': True, 'freshVirtualEnvironment': True, 'ownedPluginDirectory': True,
              'windows': platform.release(), 'machine': platform.machine(), 'cleanOperatingSystem': False}
    result['tools'] = {name: subprocess.check_output([shutil.which(executable), '--version'], text=True).strip()
                       for name, executable in (('uv','uv'),('node','node'),('pnpm','pnpm.cmd'))}
    require(Path(os.environ['UV_CACHE_DIR']).resolve().is_relative_to(work.resolve()), 'EXTERNAL_UV_CACHE')
    result['ownedEmptyInitialCache'] = True  # Newly allocated marker-owned run, never reused.
    (root / 'environment.json').write_text(json.dumps(result, indent=2), encoding='utf-8')


def live(config, root):
    from validate import Validation, digest, need
    from m3_check import M3
    from live_support import Api, wait_for, expired_token, env_values
    from engine import Engine
    from process_metrics import sample_tree, sample_processes, terminate_owned
    from PySide6.QtCore import QLockFile

    class M4Validation(Validation):
        def __init__(self):
            # The bootstrap owns this exact marker-checked run directory. Keep
            # all runtime files under work; only reports/screenshots may survive.
            self.id, self.report_root = root.name, root
            self.out = root / 'work/runtime'; self.out.mkdir()
            self.api = Api(self.id, config['apiBase'])
            self.office_enabled, self.native_only = True, False
            self.port, self.debug_port, self.offscreen = config['port'], config['debugPort'], True
            self.python_executable, self.web_root, self.process_env = sys.executable, root / 'work/web', dict(os.environ)
            self.engine = self.process = self.page = None
            self.checks, self.metrics, self.secrets = [], {'rounds': []}, []
            self.owned, self.sampler_stop, self.sampler = {}, None, None
            self.sample_lock = threading.Lock()
            self.round_number, self.rss_peak = 0, 0

        def launch(self, expect_login=True):
            start = time.monotonic()
            result = super().launch(expect_login)
            self.sampler_stop = threading.Event()
            def sample():
                while not self.sampler_stop.is_set():
                    self.sample_resources()
                    self.sampler_stop.wait(.2)
            self.sampler = threading.Thread(target=sample, daemon=True); self.sampler.start()
            self.starts.append(round(time.monotonic()-start, 3))
            return result

        def sample_resources(self):
            process = self.process
            if not process: return
            values = sample_tree(process.pid)
            with self.sample_lock:
                self.owned.update(process_fingerprints(values))
                self.rss_peak = max(self.rss_peak, sum(value['rss'] for value in values.values()) / 1024**2)

        def close_app(self):
            if not self.process: return
            self.sample_resources()
            try:
                result = super().close_app()
            except Exception:
                terminate_owned(self.owned)
                self.process = None
                raise
            finally:
                if self.sampler_stop: self.sampler_stop.set()
                if self.sampler: self.sampler.join(timeout=3)
            deadline = time.monotonic() + 5
            while surviving_owned(self.owned, sample_processes(self.owned)) and time.monotonic() < deadline:
                time.sleep(.05)
            need(not surviving_owned(self.owned, sample_processes(self.owned)), 'OWNED_PROCESS_RETAINED')
            check_ports(self.port, self.debug_port)
            lock = QLockFile(str(self.out / 'profile/instance.lock'))
            need(lock.tryLock(0), 'PROFILE_LOCK_RETAINED'); lock.unlock()
            self.owned.clear()
            return {**result, 'ownedChildrenExited': True, 'portsReleased': True, 'profileLockReleased': True}

        def remember_tokens(self):
            for key in ('accessToken', 'refreshToken'):
                token = self.page.evaluate('localStorage.getItem(' + json.dumps(key) + ')')
                need(token, 'WEB_AUTHENTICATION_MISSING')
                self.owner[key] = token; self.secrets.append(token)

        def save_settings(self, values):
            settings = self.page.invoke('get_desktop_settings')
            settings.update(values)
            self.page.invoke('save_desktop_settings', {'settings': settings})

        def login_round(self):
            for selector, value in (('.login-page__form input[type=text]', self.owner['username']),
                                    ('.login-page__form input[type=password]', self.owner['password'])):
                self.page.click(selector); self.page.command('Input.insertText', {'text': value})
            self.page.click('form button[type=submit]')
            self.page.wait("location.hash.includes('/files') && !!document.querySelector('[data-testid^=file-card]')")
            self.remember_tokens()
            need(self.page.invoke('desktop:diagnostics')['authenticated'], 'NATIVE_IDENTITY_NOT_SYNCED')
            settings = self.page.invoke('get_desktop_settings')
            if self.round_number > 1:
                need(settings['maxConcurrentDownloads'] == 2 and settings['defaultDownloadDir'] == str(self.out / 'downloads'), 'SETTINGS_NOT_RESTORED')
            self.save_settings({'defaultDownloadDir': str(self.out / 'downloads'),
                                'maxConcurrentDownloads': 2, 'downloadLimitBytesPerSec': None})
            return {'webLogin': True, 'nativeSynchronization': True, 'settingsPersisted': self.round_number > 1}

        def fixture(self):
            number = self.round_number
            values = self.api.call('POST', '/auth/login', json={'username': self.owner['username'], 'password': self.owner['password']})
            self.owner.update(values); self.secrets.extend(values.values())
            self.engine.clear_session(); self.engine.authenticate(self.owner['accessToken'], self.api.base)
            source = self.out / f'source-{number}'; source.mkdir()
            big = source / f'八兆-{number}.bin'; big.write_bytes(os.urandom(8 * 1024 * 1024))
            folder = source / f'目录-{number}'
            (folder / '空目录').mkdir(parents=True); (folder / '嵌套').mkdir()
            (folder / '零字节.txt').touch(); (folder / '嵌套/中文.txt').write_text(f'M4 {self.id} round {number}', encoding='utf-8')
            self.current_team = self.team if number % 2 == 0 else None
            request = self.request(self.current_team)
            uploaded = self.finish(self.engine.upload_files([big], request))
            directory = self.finish(self.engine.upload_files([folder], request, folder=True))
            self.big_file, self.big_path = uploaded['nodes'][0]['remoteFileId'], big
            self.folder_id, self.folder_path = directory['nodes'][0]['remoteFileId'], folder
            return {'space': 'team' if self.current_team else 'personal', 'uniqueSizeMiB': 8,
                    'nativeUpload': True, 'emptyNestedUnicodeAndZero': True}

        def visible_files(self):
            # Existing real Vue store/API, no privileged upload shortcut.
            if self.current_team:
                self.page.evaluate("location.hash='#/teams/" + str(self.team) + "/files';true")
            else:
                self.page.command('Page.reload')
            selector = '[data-testid="file-card-' + self.big_path.name + '"]'
            self.page.wait('!!document.querySelector(' + json.dumps(selector) + ')', 30)
            self.remember_tokens()
            return {'uploadedFileVisibleInVue': True}

        def tree(self, identity):
            base = f'/teams/{self.current_team}' if self.current_team else ''
            node = self.api.call('GET', f'{base}/files/{identity}', self.owner)
            if node['fileType'] == 'folder':
                children = self.api.call('GET', base + '/files', self.owner, params={'parentId': identity, 'page': 1, 'size': 100})['records']
                node['children'] = [self.tree(child['id']) for child in children]
            return node

        def wait_download(self, name):
            target = self.out / 'downloads' / name
            def finished():
                tasks = self.page.invoke('get_transfer_queue')['tasks']
                task = next((task for task in tasks if task['name'] == name), None)
                if task and task['status'] == 'error': raise RuntimeError('DOWNLOAD_TASK_FAILED')
                return task if task and task['status'] in ('done', 'instant') else None
            task = wait_for(finished, 90, interval=.2)
            need(target.exists(), 'DOWNLOAD_TARGET_MISSING')
            return target, task

        def downloads(self):
            number = self.round_number
            name = f'round-{number}.bin'
            if number == 3:
                self.save_settings({'downloadLimitBytesPerSec': 256 * 1024})
            self.page.invoke('start_desktop_download_file', {'request': {**self.request(self.current_team), 'fileId': self.big_file, 'fileName': name}})
            if number == 3:
                def in_progress():
                    return next((t for t in self.page.invoke('get_transfer_queue')['tasks'] if t['name'] == name and t['bytesDone'] >= 1024*1024), None)
                pending = wait_for(in_progress, 30, interval=.2)
                print('M4: download in progress; closing owned app', flush=True)
                self.close_app(); self.launch(expect_login=False)
                tasks = self.page.invoke('get_transfer_queue')['tasks']
                paused = next(t for t in tasks if t['id'] == pending['id'])
                need(paused['status'] == 'paused' and not (self.out / 'downloads' / name).exists(), 'DOWNLOAD_AUTOMATICALLY_RESUMED')
                self.remember_tokens()
                self.save_settings({'downloadLimitBytesPerSec': None})
                self.page.invoke('resume_transfer_node', {'nodeId': paused['id'], 'apiBaseUrl': self.api.base})
            target, _ = self.wait_download(name)
            need(digest(target) == digest(self.big_path), 'BIG_FILE_MD5_FAILED')
            tree = self.tree(self.folder_id); tree['fileName'] = f'folder-round-{number}'
            self.page.invoke('start_desktop_download_folder', {'request': {**self.request(self.current_team), 'root': tree}})
            destination, _ = self.wait_download(tree['fileName'])
            expected = {str(p.relative_to(self.folder_path)): digest(p) for p in self.folder_path.rglob('*') if p.is_file()}
            actual = {str(p.relative_to(destination)): digest(p) for p in destination.rglob('*') if p.is_file()}
            need(actual == expected and (destination / '空目录').is_dir() and (destination / '嵌套').is_dir(), 'DIRECTORY_MD5_FAILED')
            return {'nativeBridgeFileAndDirectory': True, 'allMd5': True, 'gracefulRestartResume': number == 3}

        def refresh_check(self):
            self.engine.clear_session(); self.engine.authenticate(self.owner['accessToken'], self.api.base)
            stale = expired_token(self.owner['accessToken'], env_values(ROOT.parent)); self.secrets.append(stale)
            self.engine.session.replace(self.api.base, str(self.owner['id']), stale)
            count = []
            def refresh(name, _):
                if name != 'session:refresh-required': return
                values = self.api.call('POST', '/auth/refresh', json={'refreshToken': self.owner['refreshToken']})
                self.secrets.extend(values.values()); count.append(True)
                self.engine.authenticate(values['accessToken'], self.api.base)
            self.engine.emit = refresh
            target = self.out / 'expired-token.bin'
            self.finish(self.engine.download(target, {**self.request(), 'fileId': self.big_file, 'fileName': 'expired-token.bin'}))
            need(len(count) == 1 and digest(target) == digest(self.big_path), 'REAL_401_REFRESH_FAILED')
            self.engine.emit = lambda *_: None
            return {'oneActual401Refresh': True, 'downloadMd5': True}

        def switch_check(self):
            auth = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
            router = "document.querySelector('#app').__vue_app__.config.globalProperties.$router"
            def stage(name):
                self.metrics['serverSwitchStage'] = name
                print('M4 server switch:', name, flush=True)
            target = self.api.base.replace('127.0.0.1','localhost') if '127.0.0.1' in self.api.base else self.api.base.replace('localhost','127.0.0.1')
            def open_form():
                self.page.wait("!!document.querySelector('button[aria-controls=desktop-connection-settings]')")
                # These are real Vue form controls. Native pointer/focus gestures
                # are already covered by M1; this background test must not depend
                # on focus left inside a destroyed OnlyOffice iframe.
                self.page.evaluate("document.querySelector('button[aria-controls=desktop-connection-settings]').click();true")
                self.page.wait("!!document.querySelector('#desktop-connection-settings input')")
            def save(value):
                self.page.evaluate("(() => {const e=document.querySelector('#desktop-connection-settings input');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e," + json.dumps(value) + ");e.dispatchEvent(new Event('input',{bubbles:true}));return true})()")
                self.page.evaluate("document.querySelector('#desktop-connection-settings').requestSubmit();true")
            stage('logout-original-server')
            self.page.evaluate(router + '.isReady()')
            self.page.evaluate(auth + '.logout()')
            self.page.wait("!!document.querySelector('.login-page__form')")
            stage('invalid-address-form')
            open_form(); save('file:///invalid')
            # Wait for the async validation/error notification, preserving input.
            self.page.wait("!!document.querySelector('[data-sonner-toast][data-type=error]')")
            need(self.page.evaluate("document.querySelector('#desktop-connection-settings input')?.value==='file:///invalid'"), 'FAILED_INPUT_LOST')
            stage('confirm-new-server')
            save(target); self.page.wait("!document.querySelector('#desktop-connection-settings')")
            need(self.page.evaluate("localStorage.getItem('bcd.apiBaseUrl')") == target, 'SERVER_ADDRESS_NOT_CONFIRMED')
            need(not self.page.evaluate("localStorage.getItem('accessToken')||localStorage.getItem('refreshToken')"), 'OLD_SERVER_TOKENS_RESTORED')
            stage('login-new-server')
            self.page.evaluate(auth + '.login(' + json.dumps(self.owner['username']) + ',' + json.dumps(self.owner['password']) + ')')
            need(not self.page.invoke('get_transfer_queue')['tasks'], 'OLD_SERVER_QUEUE_VISIBLE')
            stage('reload-new-server')
            self.page.evaluate('window.qtM4BeforeReload=true'); self.page.command('Page.reload')
            self.page.wait("!window.qtM4BeforeReload && !!document.querySelector('#app')?.__vue_app__")
            self.page.evaluate(auth + '.initializeSession()')
            # App mounting/auth acknowledgement can precede the initial guest
            # redirect. Operate only after the real router has finished startup.
            self.page.evaluate(router + '.isReady()')
            self.page.wait(router + ".currentRoute.value.path==='/files' && !!document.querySelector('[data-testid^=file-card]')")
            need(self.page.invoke('desktop:diagnostics')['authenticated'], 'SWITCHED_SERVER_RELOAD_FAILED')
            stage('logout-new-server')
            self.page.evaluate(auth + '.logout()'); self.page.wait("!!document.querySelector('.login-page__form')")
            stage('restore-original-address')
            open_form(); save(self.api.base); self.page.wait("!document.querySelector('#desktop-connection-settings')")
            need(not self.page.evaluate("localStorage.getItem('accessToken')||localStorage.getItem('refreshToken')"), 'SWITCH_BACK_RESTORED_OLD_IDENTITY')
            return {'invalidInputRetained': True, 'nativeAcknowledgement': True, 'noOldTokensOrQueue': True,
                    'newServerLoginAndReload': True, 'originalAddressRestoredWithoutIdentity': True, 'formControlDriver': 'DOM'}

        def videos(self):
            import imageio_ffmpeg
            store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
            results = []
            for extension in ('mp4', 'webm'):
                path = self.out / ('short.' + extension)
                codecs = ['-c:v', 'libx264', '-preset', 'ultrafast', '-c:a', 'aac', '-movflags', '+faststart'] if extension == 'mp4' else ['-c:v', 'libvpx-vp9', '-deadline', 'realtime', '-cpu-used', '8', '-c:a', 'libopus']
                subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=30',
                    '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=mono', '-t', '6', '-threads', '4', '-pix_fmt', 'yuv420p',
                    *codecs, str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                file = self.api.upload(self.owner, f'm4_{self.id}.' + extension, path.read_bytes())
                # Await the native open acknowledgement before observing the
                # reused window, so an old frame count cannot pass a new format.
                self.page.evaluate(store + '.openPreview(' + json.dumps(file) + ')')
                def playing():
                    data = self.page.invoke('desktop:diagnostics')
                    if len(data['players']) != 1: return None
                    player = data['players'][0]
                    return player if player['frames'] >= 30 and player['state'] == 'PlayingState' and player['position'] >= 500 and player['firstFrameSeconds'] is not None else None
                player = wait_for(playing, 30, interval=.2)
                need(player['size'] == [1280,720] and player['error'] == 'NoError', 'NATIVE_VIDEO_INVALID')
                results.append({'format': extension, 'actualFrames': player['frames'], 'size': player['size'], 'firstFrameSeconds': player['firstFrameSeconds']})
            return {'visibleWindowsPlatform': True, 'shortSamples': results, 'audioTrack': 'silent; decoding also covered by isolated media regression'}

        def round(self, number):
            self.round_number, self.rss_peak, self.starts = number, 0, []
            self.offscreen = number != 5
            if number == 5:
                print('M4: 第五轮将短暂显示独立测试窗口；不操作鼠标键盘。', flush=True)
            prefix = f'round-{number}-'
            before = len(self.checks)
            self.step(prefix + 'fixtures', self.fixture)
            self.step(prefix + 'launch', self.launch)
            self.step(prefix + 'login-settings', self.login_round)
            self.step(prefix + 'vue-file-list', self.visible_files)
            self.step(prefix + 'transfers-md5', self.downloads)
            if number == 1:
                self.step(prefix + 'image-text', self.preview)
                self.step(prefix + 'real-401', self.refresh_check)
            elif number == 2:
                self.step(prefix + 'html-xml', lambda: M3.plain_text(self))
            elif number == 3:
                self.step(prefix + 'pdf', self.pdf)
            elif number == 4:
                self.step(prefix + 'office-edit-save', self.office)
                self.step(prefix + 'office-readonly', self.readonly_office)
                self.step(prefix + 'server-switch', self.switch_check)
            else:
                self.step(prefix + 'native-mp4-webm', self.videos)
            if number != 4:
                self.page.evaluate("document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth').logout()")
                def media_released():
                    state = self.page.invoke('desktop:diagnostics')
                    return not state['players'] and state['mediaRequests'] == 0
                wait_for(media_released, 10, interval=.1)
                need(not self.page.invoke('get_transfer_queue')['tasks'], 'QUEUE_VISIBLE_AFTER_LOGOUT')
            self.step(prefix + 'process-ports-profile-release', self.close_app)
            self.metrics['rounds'].append({'round': number, 'status': 'passed', 'visible': not self.offscreen,
                                          'startupSeconds': self.starts, 'rssMiBPeak': round(self.rss_peak,2),
                                          'checks': len(self.checks)-before})

        def run(self):
            failed = False
            try:
                self.step('isolated-backend-fixtures', self.setup)
                for number in range(1,6): self.round(number)
            except (Exception, KeyboardInterrupt) as error:
                failed = True
                from network import DesktopError
                code = str(error) if isinstance(error, DesktopError) else type(error).__name__
                if isinstance(error, RuntimeError) and re.fullmatch('[A-Z_]{1,80}',str(error)):
                    code = str(error)
                self.checks.append({'name': 'execution', 'status': 'failed', 'code': code,
                                    'category': 'environment' if code in ('ConnectError', 'ReadTimeout', 'TEST_PORT_UNAVAILABLE') else 'implementation'})
                if self.page and self.round_number == 4:
                    try:
                        state = self.page.evaluate("(() => {const g=document.querySelector('#app')?.__vue_app__?.config.globalProperties;const a=g?.$pinia._s.get('auth');return {route:g?.$router.currentRoute.value.path,user:!!a?.user,initialized:a?.initialized,hasToken:!!localStorage.getItem('accessToken'),form:!!document.querySelector('#desktop-connection-settings')};})()")
                        self.metrics['failedPageState'] = state
                    except Exception: pass
            finally:
                if self.process:
                    try: self.close_app()
                    except Exception: failed = True
                try: self.step('run-scoped-backend-profile-cleanup', self.cleanup)
                except Exception: failed = True
                # Move only synthetic screenshots out of the disposable runtime.
                for path in self.out.glob('*.png'):
                    path.replace(self.report_root / path.name)
                report = {'runId': self.id, 'status': 'failed' if failed else 'passed', 'checks': self.checks, 'metrics': self.metrics,
                          'roundsRequired': 5, 'roundsPassed': len(self.metrics['rounds']), 'visibleMouseKeyboardAutomation': False,
                          'notExecutedRounds': [n for n in range(self.round_number+1, 6)] if failed else []}
                text = json.dumps(report, indent=2)
                need(not any(value and len(str(value)) > 8 and str(value) in text for value in self.secrets), 'REPORT_PRIVACY_CHECK_FAILED')
                (self.report_root / 'live.json').write_text(text, encoding='utf-8')
            return int(failed)

    return M4Validation().run()


def main():
    config = json.loads(sys.stdin.readline())
    root = owned_root(config['root'])
    if config.get('probe'):
        probe(root)
        return 0
    check_ports(config['port'], config['debugPort'])
    return live(config, root)


if __name__ == '__main__':
    sys.exit(main())
