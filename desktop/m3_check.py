"""Explicit M3 acceptance. No Docker management or production test controls."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from validate import Validation, need, ROOT
from live_support import wait_for


class M3(Validation):
    def __init__(self, base, suite):
        super().__init__(base, office=True, offscreen=True)
        self.suite = suite
        if suite == 'playback': self.port, self.debug_port = 5196, 9228
        self.media_cache = ROOT.parent / 'test-results/desktop/m3-media-fixtures'

    def seed(self):
        self.big_path = self.out / 'fixture.bin'; self.big_path.write_bytes(os.urandom(4096))
        self.big_file = self.api.upload(self.owner, 'fixture_' + self.id + '.bin', self.big_path.read_bytes())['id']
        return {'isolatedSmallFixture': True}

    def plain_text(self):
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        for ext in ('html', 'xml'):
            file = self.api.upload(self.owner, f'source_{self.id}.{ext}', b'<script>parent.qtUnsafePreview=true</script><p>literal source</p>')
            self.page.evaluate(store + '.openPreview(' + json.dumps(file) + ')')
            self.page.wait("document.querySelector('[role=dialog] iframe')?.contentDocument?.body.innerText.includes('<script>')")
            need(not self.page.evaluate('!!window.qtUnsafePreview'), 'EXECUTABLE_TEXT_PREVIEW')
            self.page.evaluate(store + '.closePreview()')
        return {'htmlAndXmlLiteral': True, 'noScriptExecution': True}

    def media_access(self):
        import httpx
        from engine import Engine
        from media import MediaGateway
        from live_support import expired_token, env_values
        content = os.urandom(8192)
        file = self.api.upload(self.owner, f'media_{self.id}.bin', content, self.team)
        viewer = Engine(self.out / 'media-viewer', self.api.base)
        viewer.authenticate(self.viewer['accessToken'], self.api.base)
        gateway = MediaGateway(viewer)
        refreshed = []
        try:
            _, url = gateway.register(file['id'], self.team)
            need(httpx.head(url, trust_env=False).headers.get('content-length') == str(len(content)), 'MEDIA_HEAD_FAILED')
            selected = httpx.get(url, headers={'Range': 'bytes=7-1023'}, trust_env=False)
            need(selected.status_code == 206 and selected.content == content[7:1024]
                 and selected.headers.get('content-range') == f'bytes 7-1023/{len(content)}', 'MEDIA_RANGE_FAILED')
            need(httpx.get(url, headers={'Range': 'bytes=9000-'}, trust_env=False).status_code == 416, 'MEDIA_416_FAILED')
            stale = expired_token(self.viewer['accessToken'], env_values(ROOT.parent))
            self.secrets.append(stale)
            viewer.session.replace(self.api.base, str(self.viewer['id']), stale)
            def refresh(name, _):
                if name != 'session:refresh-required': return
                values = self.api.call('POST', '/auth/refresh', json={'refreshToken': self.viewer['refreshToken']})
                self.viewer.update(values); self.secrets.extend(values.values()); refreshed.append(True)
                viewer.authenticate(self.viewer['accessToken'], self.api.base)
            viewer.emit = refresh
            need(httpx.head(url, trust_env=False).status_code == 200 and len(refreshed) == 1, 'MEDIA_REFRESH_FAILED')
            self.api.call('DELETE', f"/teams/{self.team}/members/{self.viewer['id']}", self.owner)
            need(httpx.get(url, trust_env=False).status_code == 403, 'MEDIA_PERMISSION_NOT_RECHECKED')
            viewer.clear_session()
            need(httpx.get(url, trust_env=False).status_code == 403, 'MEDIA_OLD_IDENTITY_ACCEPTED')
        finally:
            gateway.close(); viewer.close()
            self.api.call('POST', f'/teams/{self.team}/members', self.owner, json={'username': self.viewer['username'], 'role': 'VIEWER'})
        return {'authorizedHead': True, 'exactRangeAnd416': True, 'one401Refresh': True, 'revokedTeamPermission': True, 'oldIdentityDenied': True}

    def playback(self):
        import imageio_ffmpeg
        from validate import digest
        cache = self.media_cache
        need(not cache.is_symlink() and not cache.is_junction() and cache.resolve().is_relative_to((ROOT.parent / 'test-results/desktop').resolve()), 'UNSAFE_MEDIA_CACHE')
        marker = cache / '.m3-generated-media'
        need(not cache.exists() or not any(cache.iterdir()) or marker.is_file(), 'MEDIA_CACHE_NOT_OWNED')
        cache.mkdir(exist_ok=True); marker.touch()
        samples = []
        for width, height in ((1920, 1080), (3840, 2160)):
            for extension in ('mp4', 'webm'):
                name = f'{width}x{height}_{self.id}.{extension}'
                path = cache / f'{width}x{height}.{extension}'
                manifest = path.with_suffix(path.suffix + '.json')
                codecs = ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '24', '-c:a', 'aac', '-movflags', '+faststart'] if extension == 'mp4' else [
                    '-c:v', 'libvpx-vp9', '-deadline', 'realtime', '-cpu-used', '8', '-row-mt', '1', '-crf', '34', '-b:v', '0', '-c:a', 'libopus']
                if not (path.is_file() and manifest.is_file() and json.loads(manifest.read_text()) == {'fixture': 'testsrc2-120s-30fps-sdr-v1', 'md5': digest(path)}):
                    print('encoding', name, flush=True)
                    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', '-i', f'testsrc2=size={width}x{height}:rate=30',
                        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '120', '-threads', '4', '-pix_fmt', 'yuv420p',
                        *codecs, str(path)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    manifest.write_text(json.dumps({'fixture': 'testsrc2-120s-30fps-sdr-v1', 'md5': digest(path)}))
                else: print('reusing-verified-fixture', path.name, flush=True)
                team = self.team if extension == 'webm' else None
                file = self.api.upload(self.owner, name, path.read_bytes(), team)
                samples.append({'name': name, 'size': [width, height], 'fileId': file['id'], 'teamId': team, 'bytes': path.stat().st_size})
        report = self.out / 'playback.json'
        config = {'apiBase': self.api.base, 'profile': str(self.out / 'profile'),
                  'port': self.port, 'debugPort': self.debug_port, 'samples': samples, 'report': str(report)}
        env = {**os.environ, 'PYTHONUTF8': '1', 'QT_MEDIA_BACKEND': 'ffmpeg'}
        env.pop('QT_QPA_PLATFORM', None); env.pop('QTWEBENGINE_CHROMIUM_FLAGS', None)
        for port in (self.port, self.debug_port):
            import socket
            with socket.socket() as probe: probe.bind(('127.0.0.1', port))
        self.process = subprocess.Popen([sys.executable, str(ROOT / 'tests/m3_player_worker.py')], stdin=subprocess.PIPE,
            stdout=None, stderr=subprocess.DEVNULL, env=env, cwd=ROOT, text=True)
        self.process.stdin.write(json.dumps(config) + '\n'); self.process.stdin.close()
        from cdp import Cdp
        self.page = Cdp(self.debug_port, expected_origin=f'http://127.0.0.1:{self.port}')
        self.page.wait("!!document.querySelector('.login-page__form input')")
        self.page.evaluate("document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth').login(" + json.dumps(self.owner['username']) + ',' + json.dumps(self.owner['password']) + ')')
        code = self.process.wait(timeout=700); self.process = None
        self.page.ws.close(); self.page = None
        self.metrics['playback'] = json.loads(report.read_text())
        need(code == 0, 'PLAYBACK_GATE_FAILED')
        return {'allFourSamplesPassed': True, 'actual4kFrames': True, 'report': 'playback.json'}

    def interaction_launch(self):
        from cdp import Cdp
        import socket
        for port in (self.port, self.debug_port):
            with socket.socket() as probe: probe.bind(('127.0.0.1', port))
        config = {'profile': str(self.out / 'profile'), 'apiBase': self.api.base, 'port': self.port,
                  'debugPort': self.debug_port, 'observations': str(self.out / 'interaction-state.json')}
        env = {**os.environ, 'PYTHONUTF8': '1', 'QT_MEDIA_BACKEND': 'ffmpeg'}
        env.pop('QT_QPA_PLATFORM', None); env.pop('QTWEBENGINE_CHROMIUM_FLAGS', None)
        self.process = subprocess.Popen([sys.executable, str(ROOT / 'tests/m3_interaction_worker.py')], stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True, env=env, cwd=ROOT)
        self.process.stdin.write(json.dumps(config) + '\n'); self.process.stdin.close()
        self.page = Cdp(self.debug_port, expected_origin=f'http://127.0.0.1:{self.port}')
        self.page.wait("!!document.querySelector('.login-page__form input')")
        return {'independentVisibleWindow': True, 'readOnlyTestInstrumentation': True}

    def interaction(self):
        import imageio_ffmpeg
        import base64
        video = self.out / 'controls.mp4'
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', '-i', 'testsrc2=size=960x540:rate=30',
            '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', '30', '-threads', '4', '-c:v', 'libx264', '-preset', 'ultrafast',
            '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-movflags', '+faststart', str(video)], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        first = self.api.upload(self.owner, 'M3_controls_' + self.id + '.mp4', video.read_bytes())
        second = self.api.upload(self.owner, 'M3_replacement_' + self.id + '.mp4', video.read_bytes(), self.team)
        bad = self.api.upload(self.owner, 'M3_invalid_' + self.id + '.mp4', b'This is deliberately not a media container.')
        image = self.api.upload(self.owner, 'M3_image_' + self.id + '.png', base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nS8AAAAASUVORK5CYII='))
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        self.page.evaluate(store + '.openPreview(' + json.dumps(image) + ')')
        self.page.wait("document.querySelector('[role=dialog] img')?.naturalWidth>0")
        self.page.evaluate("window.qtM3Replies=[];window.addEventListener('bcd:response', e=>{const v=JSON.parse(e.detail);window.qtM3Replies.push({error:v.error})});true")
        self.page.evaluate(store + '.openPreview(' + json.dumps(first) + ');true')
        state = self.out / 'interaction-state.json'
        def observed(): return json.loads(state.read_text(encoding='utf-8'))
        wait_for(lambda: observed()['players'] and observed()['players'][0]['frames'] > 3)
        original = observed()['players'][0]['windowId']
        print('INTERACTION_CONTROLS', self.out, flush=True)
        wait_for(lambda: (self.out / 'controls-accepted.json').is_file(), 240)
        accepted = json.loads((self.out / 'controls-accepted.json').read_text(encoding='utf-8'))
        need(all(accepted.get(k) for k in ('playPause', 'spaceWithChildFocus', 'seekOnRelease', 'volume', 'fullscreenEscape', 'replay', 'pictureAndAudio')), 'INTERACTION_NOT_ACCEPTED')
        need(observed()['seekReleases'] > 0, 'NO_REAL_SEEK_RELEASE')
        self.page.evaluate(store + '.openPreview(' + json.dumps(second) + ',' + json.dumps({'teamId': self.team}) + ');true')
        wait_for(lambda: observed()['opens'] >= 2 and observed()['players'][0]['frames'] > 3)
        need(len(observed()['players']) == 1 and observed()['players'][0]['windowId'] == original, 'PLAYER_NOT_REUSED')
        need(self.page.evaluate(store + '.open && !!document.querySelector("[role=dialog] img")'), 'WEB_PREVIEW_CLOSED_BY_VIDEO')
        self.page.evaluate(store + '.closePreview()')
        need(len(observed()['players']) == 1, 'CLOSING_WEB_CLOSED_VIDEO')
        self.page.evaluate(store + '.openPreview(' + json.dumps(bad) + ');true')
        wait_for(lambda: observed()['players'] and observed()['players'][0]['retryVisible'])
        print('INTERACTION_RETRY', self.out, flush=True)
        wait_for(lambda: observed()['opens'] >= 4, 120)
        wait_for(lambda: observed()['players'][0]['retryVisible'])
        need(observed()['players'][0]['windowId'] == original, 'RETRY_CREATED_ANOTHER_WINDOW')
        self.page.evaluate(store + '.openPreview(' + json.dumps(first) + ');true')
        wait_for(lambda: observed()['players'][0]['frames'] > 3 and not observed()['players'][0]['retryVisible'])
        self.page.evaluate(store + '.openPreview(' + json.dumps(image) + ')')
        self.page.evaluate("document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth').logout()")
        wait_for(lambda: not observed()['players'] and not observed()['capabilities'] and not observed()['activeMediaRequests'])
        need(self.page.evaluate('!' + store + '.open'), 'WEB_PREVIEW_RETAINED_AFTER_LOGOUT')
        return {**accepted, 'oneWindowReused': True, 'webAndVideoIndependent': True, 'unsupportedAndRetry': True,
                'validVideoRecovers': True, 'logoutClosesBothAndConnections': True}

    def run(self):
        failed = False
        operations = [('isolated-backend-fixtures', self.setup)]
        if self.suite == 'functional':
            operations += [('seed', self.seed), ('real-media-authorization-and-ranges', self.media_access), ('real-vue-launch', self.launch), ('login-reload', self.login),
                ('image-text-preview', self.preview), ('html-xml-plain-text', self.plain_text), ('pdf-preview', self.pdf),
                ('onlyoffice-edit-save', self.office), ('onlyoffice-readonly', self.readonly_office),
                ('bridge-security', self.security), ('logout-and-native-session', self.logout_and_switch), ('application-exit', self.close_app)]
        elif self.suite == 'interaction':
            operations += [('seed', self.seed), ('visible-launch', self.interaction_launch), ('login', self.login),
                ('native-player-interaction', self.interaction), ('application-exit', self.close_app)]
        elif self.suite == 'access':
            operations += [('real-media-authorization-and-ranges', self.media_access)]
        else:
            operations += [('1080p-and-4k-playback', self.playback)]
        try:
            for name, operation in operations: self.step(name, operation)
        except Exception: failed = True
        finally:
            if self.process:
                try: self.close_app()
                except Exception: failed = True
            if self.suite == 'playback' and not failed and (self.media_cache / '.m3-generated-media').is_file():
                need(self.media_cache.resolve().is_relative_to((ROOT.parent / 'test-results/desktop').resolve())
                     and not self.media_cache.is_symlink() and not self.media_cache.is_junction(), 'UNSAFE_MEDIA_CACHE')
                shutil.rmtree(self.media_cache)
            try: self.step('run-scoped-cleanup', self.cleanup)
            except Exception: failed = True
            # Only reports and synthetic screenshots survive. Remove this run's
            # explicitly allocated payloads and engine databases as well.
            for path in self.out.iterdir():
                if path.suffix in ('.json', '.png'): continue
                need(path.resolve().is_relative_to(self.out.resolve()) and not path.is_symlink() and not path.is_junction(), 'UNSAFE_CLEANUP_PATH')
                if path.is_dir(): shutil.rmtree(path)
                else: path.unlink()
            report = json.dumps({'runId': self.id, 'suite': self.suite, 'checks': self.checks, 'metrics': self.metrics}, indent=2)
            need(not any(value and len(str(value)) > 8 and str(value) in report for value in self.secrets), 'REPORT_PRIVACY_CHECK_FAILED')
            (self.out / 'results.json').write_text(report, encoding='utf-8')
            print('report', self.out / 'results.json', flush=True)
        return int(failed)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--api-base', default='http://127.0.0.1:8080/api/v1')
    parser.add_argument('--suite', choices=['functional', 'access', 'playback', 'interaction'], required=True)
    args = parser.parse_args()
    sys.exit(M3(args.api_base, args.suite).run())
