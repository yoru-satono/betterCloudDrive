"""Shared, headless Qt development checks. Never starts Docker or a live client."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from m4_support import M4Error, clean_environment, owned_root, remove_owned, require

ROOT = Path(__file__).resolve().parent


def web_fingerprint(directory):
    """Include timestamps to detect even an identical rebuild of daily assets."""
    if not directory.exists(): return None
    require(not directory.is_symlink() and not directory.is_junction(), 'UNSAFE_DAILY_WEB_PATH')
    digest = hashlib.sha256()
    for path in sorted(directory.rglob('*')):
        require(not path.is_symlink() and not path.is_junction(), 'UNSAFE_DAILY_WEB_PATH')
        if path.is_file():
            digest.update(str(path.relative_to(directory)).encode())
            digest.update(str(path.stat().st_mtime_ns).encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


class Verification:
    def __init__(self, existing_root=None):
        require(platform.python_version() == '3.12.13', 'VERIFY_REQUIRES_PYTHON_3_12_13')
        names = ('uv', 'node', 'pnpm.cmd' if os.name == 'nt' else 'pnpm')
        found = {name: shutil.which(name) for name in names}
        require(all(found.values()), 'MISSING_UV_NODE_OR_PNPM')
        self.uv, self.pnpm = found['uv'], found[names[-1]]
        self.id = Path(existing_root).name if existing_root else secrets.token_hex(5)
        self.out = ROOT.parent / 'test-results/desktop' / self.id
        if existing_root:
            require(Path(existing_root).resolve() == self.out.resolve(), 'WORKER_RUN_OUTSIDE_WORKSPACE')
        if not existing_root:
            self.out.mkdir(parents=True)
            (self.out / '.m4-run').write_text(self.id, encoding='utf-8')
            (self.out / '.qt-verification').write_text(self.id, encoding='utf-8')
        owned_root(self.out)
        purpose = self.out / '.qt-verification'
        require(purpose.is_file() and not purpose.is_symlink() and purpose.read_text(encoding='utf-8') == self.id,
                'NOT_A_QT_VERIFICATION_RUN')
        self.work = self.out / 'work'; self.work.mkdir(exist_ok=bool(existing_root))
        temporary = self.work / 'tmp'; temporary.mkdir(exist_ok=bool(existing_root))
        self.env = clean_environment(os.environ, [Path(path).parent for path in found.values()])
        self.env.update(TEMP=str(temporary), TMP=str(temporary), TMPDIR=str(temporary), QT_QPA_PLATFORM='offscreen',
                        UV_PROJECT_ENVIRONMENT=str(ROOT / '.venv'), UV_CACHE_DIR=str(self.work / 'uv-cache'))
        self.python = ROOT / ('.venv/Scripts/python.exe' if os.name == 'nt' else '.venv/bin/python')
        self.checks = []

    def command(self, name, command, cwd=ROOT):
        print(name, 'running', flush=True)
        start = time.monotonic()
        sys.path.insert(0, str(Path(__file__).resolve().parent / 'tests'))
        from process_metrics import sample_tree, sample_processes, terminate_owned
        from m4_support import process_fingerprints, surviving_owned
        process = subprocess.Popen([str(v) for v in command], cwd=cwd, env=self.env, text=True, encoding='utf-8',
                                   errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   **({'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}))
        owned = {}
        try:
            while True:
                owned.update(process_fingerprints(sample_tree(process.pid)))
                try:
                    output = process.communicate(timeout=.2)[0]
                    break
                except subprocess.TimeoutExpired:
                    require(time.monotonic()-start < 600, 'VERIFY_COMMAND_TIMEOUT')
        finally:
            if process.poll() is None:
                terminate_owned(owned)
                process.wait(timeout=10)
            deadline = time.monotonic()+5
            while surviving_owned(owned, sample_processes(owned)) and time.monotonic() < deadline:
                time.sleep(.05)
            remaining = surviving_owned(owned, sample_processes(owned))
            if remaining:
                terminate_owned({pid: owned[pid] for pid in remaining})
                raise M4Error('VERIFY_CHILD_PROCESS_RETAINED')
        check = {'name': name, 'status': 'passed' if process.returncode == 0 else 'failed',
                 'exitCode': process.returncode, 'seconds': round(time.monotonic()-start, 2), 'ownedChildrenExited': True}
        summaries = re.findall(r'\b(\d+) passed\b', output)
        if summaries: check['testSummary'] = summaries[-1] + ' passed'
        self.checks.append(check)
        if process.returncode:
            # Assertion output can contain inherited environment values. Keep
            # raw output in memory only; report safe test identifiers and codes.
            for identity in re.findall(r'(?m)^FAILED ([a-zA-Z0-9_./:-]+)', output):
                print('Failed test:', identity, flush=True)
            print(name, 'failed; exit code', process.returncode, flush=True)
            raise M4Error(name.upper().replace('-', '_') + '_FAILED')
        print(name, 'passed', flush=True)

    def run(self):
        failed, before = False, None
        try:
            before = web_fingerprint(ROOT / 'web-dist')
            self.command('locked-development-dependencies', [self.uv, '--no-config', 'sync', '--locked', '--dev',
                         '--project', ROOT, '--python', sys.executable])
            self.command('python-regression', [self.python, '-m', 'pytest', '-q', '--basetemp', self.work / 'pytest',
                         '-o', 'cache_dir=' + str(self.work / 'pytest-cache'), '--junitxml', self.work / 'junit.xml'])
            junit = self.work / 'junit.xml'
            if junit.exists():
                skipped = [node for node in ET.parse(junit).iter('skipped')]
                require(all(node.get('message', '').startswith('PLATFORM_NOT_APPLICABLE:') for node in skipped), 'APPLICABLE_TEST_SKIPPED')
                self.checks.append({'name': 'platform-applicability', 'status': 'passed',
                                    'notApplicable': [node.get('message') for node in skipped]})
            frontend = ROOT.parent / 'frontend'
            self.command('desktop-types', [self.pnpm, 'exec', 'vue-tsc', '--noEmit', '-p', '../desktop/tsconfig.json'], frontend)
            self.command('desktop-vitest', [self.pnpm, 'exec', 'vitest', 'run', '--config', '../desktop/vitest.config.mjs'], frontend)
            self.command('isolated-web-build', [self.python, ROOT / 'build.py', '--out-dir', self.work / 'web'])
        except (Exception, KeyboardInterrupt) as error:
            failed = True
            self.checks.append({'name': 'execution', 'status': 'failed',
                                'code': str(error) if isinstance(error, M4Error) else type(error).__name__})
        finally:
            try:
                require(before == web_fingerprint(ROOT / 'web-dist'), 'DAILY_WEB_CHANGED')
                self.checks.append({'name': 'daily-web-preserved', 'status': 'passed', 'fingerprint': before})
            except Exception as error:
                failed = True
                self.checks.append({'name': 'daily-web-preserved', 'status': 'failed', 'code': type(error).__name__})
            try:
                remove_owned(self.out, self.work)
                self.checks.append({'name': 'run-resources-cleaned', 'status': 'passed'})
            except Exception as error:
                failed = True
                self.checks.append({'name': 'run-resources-cleaned', 'status': 'failed', 'code': type(error).__name__})
            report = {'runId': self.id, 'module': 'qt', 'status': 'failed' if failed else 'passed',
                      'checks': self.checks, 'platform': platform.system(), 'python': platform.python_version(),
                      'scope': 'development regression; no live-backend, native interaction or clean-system acceptance',
                      'backendStarted': False, 'retainedResources': ['non-sensitive-report'] if not self.work.exists() else ['cleanup-failed-work']}
            (self.out / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print('Qt report:', self.out / 'results.json', flush=True)
        return int(failed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if args.worker:
            return Verification(existing_root=owned_root(args.worker)).run()
        # Keep the bootstrap stdlib-only. psutil is imported only in the locked
        # project interpreter after development dependencies are available.
        run = Verification()
        try:
            print('Preparing locked verification environment', flush=True)
            prepared = subprocess.run([run.uv, '--no-config', 'sync', '--locked', '--dev', '--project', str(ROOT),
                                      '--python', sys.executable], cwd=ROOT, env=run.env,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
            require(prepared.returncode == 0, 'VERIFY_ENVIRONMENT_PREPARATION_FAILED')
            process = subprocess.run([str(run.python), str(ROOT / 'verify.py'), '--worker', str(run.out)], cwd=ROOT, env=run.env)
            return process.returncode
        finally:
            if not (run.out / 'results.json').exists():
                try: remove_owned(run.out, run.work); cleanup = 'passed'
                except Exception: cleanup = 'failed'
                (run.out / 'results.json').write_text(json.dumps({'runId': run.id, 'status': 'failed',
                    'code': 'VERIFICATION_WORKER_NOT_COMPLETED', 'cleanup': cleanup}), encoding='utf-8')
    except M4Error as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
