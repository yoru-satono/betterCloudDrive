"""M4 bootstrap: fresh uv-managed Python/cache/environment; stdlib only.

Run from the repository root with:
  uv run --no-project --python 3.12.13 desktop/m4_check.py
Services must already be running. This script never manages Docker or the OS.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit

from m4_support import M4Error, check_ports, clean_environment, owned_root, remove_owned, require

ROOT = Path(__file__).resolve().parent


class Bootstrap:
    def __init__(self, base, port, debug_port):
        check_ports(port, debug_port)
        parsed = urlsplit(base)
        require(parsed.scheme in ('http', 'https') and parsed.hostname in ('localhost', '127.0.0.1')
                and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
                and parsed.path.rstrip('/') == '/api/v1', 'M4_REQUIRES_LOOPBACK_API')
        self.tools = {name: shutil.which(name) for name in ('uv', 'node', 'pnpm.cmd')}
        require(all(self.tools.values()), 'MISSING_UV_NODE_OR_PNPM')
        self.id = secrets.token_hex(5)
        self.out = ROOT.parent / 'test-results/desktop' / self.id
        self.out.mkdir(parents=True)
        (self.out / '.m4-run').write_text(self.id, encoding='utf-8')
        owned_root(self.out)
        self.work = self.out / 'work'; self.work.mkdir()
        temporary = self.work / 'tmp'; temporary.mkdir()
        self.base, self.port, self.debug_port = base.rstrip('/'), port, debug_port
        self.checks = []
        self.env = clean_environment(os.environ, [Path(p).parent for p in self.tools.values()])
        self.env.update(UV_CACHE_DIR=str(self.work / 'uv-cache'), UV_PROJECT_ENVIRONMENT=str(self.work / 'env'),
                        UV_PYTHON_INSTALL_DIR=str(self.work / 'python'), TEMP=str(temporary), TMP=str(temporary))
        self.python = self.work / 'env/Scripts/python.exe'

    def command(self, label, command, cwd=ROOT, timeout=900, env=None, stdin=None):
        start = time.monotonic()
        print(label, 'running', flush=True)
        p = subprocess.run([str(v) for v in command], cwd=cwd, env=env or self.env, input=stdin,
                           text=True, encoding='utf-8', errors='replace', stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=timeout)
        self.checks.append({'name': label, 'status': 'passed' if p.returncode == 0 else 'failed',
                            'seconds': round(time.monotonic()-start, 2), 'exitCode': p.returncode})
        summary = re.findall(r'\d+ passed(?:[^\r\n]*)', p.stdout)
        if summary: self.checks[-1]['testSummary'] = summary[-1].strip()
        # Install/build/unit output contains no live credentials. Drop URLs and
        # literal JWTs before showing even a short failure excerpt.
        if p.returncode:
            output = re.sub(r'https?://\S+|eyJ[\w-]+\.[\w-]+\.[\w-]+', '[redacted]', p.stdout)
            print(output[-2500:], flush=True)
            raise M4Error(label.upper().replace('-', '_') + '_FAILED')
        print(label, 'passed', flush=True)
        return p.stdout

    def prepare(self):
        uv = self.tools['uv']
        self.command('fresh-managed-python', [uv, '--no-config', 'python', 'install', '3.12.13',
                     '--install-dir', self.work / 'python', '--no-bin', '--no-registry'])
        self.command('locked-fresh-environment', [uv, '--no-config', 'sync', '--locked', '--dev',
                     '--project', ROOT, '--python', '3.12.13', '--managed-python'])
        frontend = ROOT.parent / 'frontend'
        lock = frontend / 'pnpm-lock.yaml'
        before = hashlib.sha256(lock.read_bytes()).digest()
        self.command('frontend-frozen-lock', [self.tools['pnpm.cmd'], 'install', '--frozen-lockfile',
                     '--lockfile-only', '--offline', '--ignore-scripts'], cwd=frontend)
        require(hashlib.sha256(lock.read_bytes()).digest() == before, 'FRONTEND_LOCK_CHANGED')
        probe = {'root': str(self.out), 'probe': True}
        self.command('isolated-runtime-probe', [self.python, ROOT / 'tests/m4_live.py'], stdin=json.dumps(probe)+'\n')
        tests_env = {**self.env, 'QT_QPA_PLATFORM': 'offscreen'}
        self.command('python-regression', [self.python, '-m', 'pytest', '-q', '--basetemp', self.work / 'pytest'], env=tests_env)
        self.command('desktop-types', [self.tools['pnpm.cmd'], 'exec', 'vue-tsc', '--noEmit', '-p', '../desktop/tsconfig.json'], cwd=frontend)
        self.command('desktop-vitest', [self.tools['pnpm.cmd'], 'exec', 'vitest', 'run', '--config', '../desktop/vitest.config.mjs'], cwd=frontend)
        self.command('isolated-web-build', [self.tools['pnpm.cmd'], 'exec', 'vite', 'build', '--config', '../desktop/vite.config.mjs',
                     '--outDir', self.work / 'web', '--emptyOutDir'], cwd=frontend, env={**self.env, 'BCD_DESKTOP_API_BASE': self.base})

    def run(self):
        failed = False
        try:
            require(os.name == 'nt' and platform.machine().lower() in ('amd64', 'x86_64'), 'M4_WINDOWS_X64_ONLY')
            self.prepare()
            config = {'root': str(self.out), 'apiBase': self.base, 'port': self.port, 'debugPort': self.debug_port}
            # The live runner prints progress only; its report never contains
            # credentials, and subprocess command lines contain no credentials.
            print('five-round-live running', flush=True)
            code = self.run_live(config)
            self.checks.append({'name': 'five-round-live', 'status': 'passed' if code == 0 else 'failed', 'exitCode': code})
            require(code == 0, 'M4_LIVE_FAILED')
            live = json.loads((self.out / 'live.json').read_text(encoding='utf-8'))
            require(live['status'] == 'passed' and live['roundsPassed'] == 5, 'INCOMPLETE_LIVE_ACCEPTANCE')
        except (Exception, KeyboardInterrupt) as error:
            failed = True
            self.checks.append({'name': 'execution', 'status': 'failed', 'code': str(error) if isinstance(error, M4Error) else type(error).__name__})
        finally:
            # A live runner failure still performs backend cleanup itself. Never
            # report an environment cleanup failure as successful acceptance.
            try:
                remove_owned(self.out, self.work)
                self.checks.append({'name': 'isolated-environment-cleanup', 'status': 'passed'})
            except Exception as error:
                failed = True
                self.checks.append({'name': 'isolated-environment-cleanup', 'status': 'failed', 'code': type(error).__name__})
            report = {'runId': self.id, 'scope': 'current Windows; fresh uv environment, not a clean OS', 'checks': self.checks,
                      'status': 'failed' if failed else 'passed', 'cleanSystem': 'not-tested', 'roundsRequired': 5,
                      'notTested': ['clean Windows OS', 'installer/signing/auto-update', 'Linux/macOS', 'long-duration stress',
                                    'native picker gestures (reuse previous acceptance)']}
            for name in ('environment', 'live'):
                path = self.out / (name + '.json')
                if path.is_file():
                    try: report[name] = json.loads(path.read_text(encoding='utf-8'))
                    except (OSError, ValueError): report[name] = {'status': 'invalid-report'}; report['status'] = 'failed'; failed = True
            (self.out / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print('report', self.out / 'results.json', flush=True)
        return int(failed)

    def run_live(self, config, worker=None):
        sys.path.insert(0, str(ROOT / 'tests'))
        from process_metrics import sample_tree, sample_processes, terminate_owned
        from m4_support import process_fingerprints, surviving_owned
        process = subprocess.Popen([str(self.python), str(worker or ROOT / 'tests/m4_live.py')], cwd=ROOT, env=self.env,
                                   stdin=subprocess.PIPE, text=True, encoding='utf-8',
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        owned = {}
        try:
            process.stdin.write(json.dumps(config)+'\n'); process.stdin.close()
            deadline = time.monotonic() + 900
            while process.poll() is None:
                owned.update(process_fingerprints(sample_tree(process.pid)))
                require(time.monotonic() < deadline, 'LIVE_RUN_TIMEOUT')
                time.sleep(.2)
            return process.returncode
        finally:
            if process.poll() is None:
                process.send_signal(signal.CTRL_BREAK_EVENT)
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired: terminate_owned(owned)
            remaining = surviving_owned(owned, sample_processes(owned))
            if remaining:
                terminate_owned({pid: owned[pid] for pid in remaining})
                raise M4Error('LIVE_CHILD_PROCESS_RETAINED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-base', default='http://127.0.0.1:8080/api/v1')
    parser.add_argument('--port', type=int, default=5198)
    parser.add_argument('--debug-port', type=int, default=9230)
    args = parser.parse_args()
    try:
        return Bootstrap(args.api_base, args.port, args.debug_port).run()
    except M4Error as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
