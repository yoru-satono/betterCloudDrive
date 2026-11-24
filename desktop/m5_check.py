"""M5 isolated state acceptance and explicitly retained uv trial environments.

Run from the repository root. No mode starts Docker or stops another client.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import sys

from m4_check import Bootstrap, ROOT
from m4_support import M4Error, clean_environment, owned_root, remove_owned, require


def runtime_digest():
    digest = hashlib.sha256()
    for name in ('app.py', 'engine.py', 'transport.py', 'network.py', 'media.py', 'media_strings.py',
                 'uv.lock', 'pyproject.toml', 'assets/app.ico'):
        digest.update(name.encode()); digest.update((ROOT / name).read_bytes())
    return digest.hexdigest()


def trial_root(identity):
    require(re.fullmatch('[0-9a-f]{10}', identity or ''), 'INVALID_TRIAL_ID')
    root = owned_root(ROOT.parent / 'test-results/desktop' / identity)
    manifest = root / 'trial.json'
    require(not manifest.is_symlink() and manifest.is_file(), 'TRIAL_MANIFEST_MISSING')
    try:
        data = json.loads(manifest.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        raise M4Error('INVALID_TRIAL_MANIFEST') from None
    require(data.get('kind') == 'm5-trial' and data.get('runId') == identity
            and data.get('repository') == str(ROOT.parent) and data.get('state') == 'ready', 'TRIAL_NOT_READY')
    require(type(data.get('port')) is int and 1 <= data['port'] <= 65535, 'INVALID_TRIAL_PORT')
    return root, data


@contextmanager
def trial_guard(root):
    """Serialize launch/cleanup without holding files inside disposable work."""
    path = root / 'trial.guard'
    require(not path.is_symlink() and not path.is_junction(), 'UNSAFE_TRIAL_GUARD')
    with path.open('a+b') as stream:
        stream.seek(0, 2)
        if stream.tell() == 0: stream.write(b'0'); stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise M4Error('TRIAL_IN_USE') from None
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def require_trial_idle(root):
    # A stale Qt lock is deliberately refused too. A normal launch lets Qt
    # resolve its own stale lock; cleanup never guesses whether it can delete it.
    require(not (root / 'work/profile/instance.lock').exists(), 'TRIAL_PROFILE_LOCK_PRESENT')


def launch_trial(identity):
    root, data = trial_root(identity)
    with trial_guard(root):
        require_trial_idle(root)
        require(data['runtimeDigest'] == runtime_digest(), 'TRIAL_RUNTIME_CHANGED_PREPARE_AGAIN')
        work = root / 'work'
        for name in ('env/Scripts/python.exe', 'web/index.html', 'profile/.bcd-qt-desktop'):
            target = work / name
            require(target.is_file() and target.resolve().is_relative_to(root), 'TRIAL_RESOURCE_MISSING')
        uv = shutil.which('uv'); require(uv, 'MISSING_UV')
        with socket.socket() as probe:
            try: probe.bind(('127.0.0.1', data['port']))
            except OSError: raise M4Error('TEST_PORT_UNAVAILABLE') from None
        env = clean_environment(os.environ, [Path(uv).parent])
        env.update(UV_PROJECT_ENVIRONMENT=str(work / 'env'), UV_CACHE_DIR=str(work / 'uv-cache'),
                   UV_PYTHON_INSTALL_DIR=str(work / 'python'), TEMP=str(work / 'tmp'), TMP=str(work / 'tmp'))
        # Fixed command shape: no CDP, no credentials, no environment sync while
        # the trial is running. Saved connection settings remain in its Profile.
        return subprocess.call([uv, '--no-config', 'run', '--locked', '--no-sync', '--project', str(ROOT),
            'python', str(ROOT / 'app.py'), '--profile', str(work / 'profile'), '--web-root', str(work / 'web'),
            '--api-base', data['apiBase'], '--port', str(data['port'])], cwd=ROOT, env=env)


def cleanup_trial(identity):
    root, data = trial_root(identity)
    with trial_guard(root):
        require_trial_idle(root)
        try:
            remove_owned(root, root / 'work')
        except Exception as error:
            (root / 'trial-cleanup.json').write_text(json.dumps({'status': 'failed', 'code': type(error).__name__}), encoding='utf-8')
            raise
        data['state'] = 'cleaned'
        (root / 'trial.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
        (root / 'trial-cleanup.json').write_text(json.dumps({'status': 'passed', 'runId': identity}), encoding='utf-8')
    print('Trial resources cleaned:', identity)
    return 0


class M5Bootstrap(Bootstrap):
    def run(self, prepare_trial=False):
        failed, retained = False, False
        try:
            require(os.name == 'nt' and platform.machine().lower() in ('amd64', 'x86_64'), 'M5_WINDOWS_X64_ONLY')
            self.prepare()
            if prepare_trial:
                remove_owned(self.out, self.work / 'pytest')
                profile = self.work / 'profile'; profile.mkdir()
                (profile / '.bcd-qt-desktop').touch()
                manifest = {'kind': 'm5-trial', 'runId': self.id, 'repository': str(ROOT.parent), 'state': 'ready',
                            'apiBase': self.base, 'port': self.port, 'runtimeDigest': runtime_digest(),
                            'accountsCreated': 0, 'cdpEnabled': False, 'manualObservation': 'not-executed'}
                (self.out / 'trial.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
                retained = True
                self.checks.append({'name': 'trial-ready', 'status': 'passed', 'profileEmpty': True, 'cdpEnabled': False})
            else:
                code = self.run_live({'root': str(self.out), 'apiBase': self.base, 'port': self.port,
                                      'debugPort': self.debug_port}, worker=ROOT / 'tests/m5_live.py')
                require(code == 0, 'M5_LIVE_FAILED')
                live = json.loads((self.out / 'live.json').read_text(encoding='utf-8'))
                require(live.get('status') == 'passed' and live.get('requiredComplete') is True, 'INCOMPLETE_M5_ACCEPTANCE')
                self.checks.append({'name': 'm5-live', 'status': 'passed'})
        except (Exception, KeyboardInterrupt) as error:
            failed = True
            self.checks.append({'name': 'execution', 'status': 'failed',
                                'code': str(error) if isinstance(error, M4Error) else type(error).__name__})
        finally:
            if not retained:
                try:
                    remove_owned(self.out, self.work)
                    self.checks.append({'name': 'isolated-environment-cleanup', 'status': 'passed'})
                except Exception as error:
                    failed = True
                    self.checks.append({'name': 'isolated-environment-cleanup', 'status': 'failed', 'code': type(error).__name__})
            report = {'runId': self.id, 'stage': 'M5', 'status': 'failed' if failed else 'passed', 'checks': self.checks,
                      'manualObservation': 'not-executed', 'cleanWindows': 'not-tested',
                      'retainedResources': ['interpreter', 'environment', 'cache', 'web', 'empty-profile'] if retained else [],
                      'historicalCleanupExceptions': ['M3 three SQLite states', 'M4 pytest-109/110/111; policy blocked; not retried',
                                                      'M5 initial m5-unit-scratch; policy blocked; not retried']}
            for name in ('environment', 'live'):
                path = self.out / (name + '.json')
                if path.is_file():
                    try: report[name] = json.loads(path.read_text(encoding='utf-8'))
                    except (OSError, ValueError): report['status'] = 'failed'; failed = True
            (self.out / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print('report', self.out / 'results.json', flush=True)
            if retained:
                print('Trial ID:', self.id)
                print('Launch: uv run --no-project --python 3.12.13 desktop/m5_check.py --launch-trial', self.id)
                print('Cleanup: uv run --no-project --python 3.12.13 desktop/m5_check.py --cleanup-trial', self.id)
        return int(failed)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--prepare-trial', action='store_true')
    mode.add_argument('--launch-trial', metavar='ID')
    mode.add_argument('--cleanup-trial', metavar='ID')
    parser.add_argument('--api-base')
    parser.add_argument('--port', type=int)
    parser.add_argument('--debug-port', type=int)
    args = parser.parse_args(argv)
    if (args.launch_trial or args.cleanup_trial) and any(v is not None for v in (args.api_base, args.port, args.debug_port)):
        parser.error('Existing trials use their saved configuration; omit --api-base, --port and --debug-port')
    try:
        if args.launch_trial: return launch_trial(args.launch_trial)
        if args.cleanup_trial: return cleanup_trial(args.cleanup_trial)
        return M5Bootstrap(args.api_base or 'http://127.0.0.1:8080/api/v1',
                           args.port if args.port is not None else 5199,
                           args.debug_port if args.debug_port is not None else 9231).run(args.prepare_trial)
    except (Exception, KeyboardInterrupt) as error:
        print(str(error) if isinstance(error, M4Error) else type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
