import json
from pathlib import Path
import socket

import pytest

import m5_check as m5
from m4_support import M4Error


@pytest.fixture
def trial(tmp_path, monkeypatch):
    repository = tmp_path/'repo'; desktop = repository/'desktop'; desktop.mkdir(parents=True)
    monkeypatch.setattr(m5, 'ROOT', desktop)
    root = repository/'test-results/desktop/0123456789'; root.mkdir(parents=True)
    (root/'.m4-run').write_text(root.name)
    work = root/'work'
    for name in ('env/Scripts/python.exe','web/index.html','profile/.bcd-qt-desktop'):
        path = work/name; path.parent.mkdir(parents=True, exist_ok=True); path.touch()
    (work/'tmp').mkdir()
    manifest = {'kind':'m5-trial','runId':root.name,'repository':str(repository),'state':'ready',
                'runtimeDigest':'snapshot','apiBase':'http://127.0.0.1:8080/api/v1','port':5199}
    (root/'trial.json').write_text(json.dumps(manifest))
    monkeypatch.setattr(m5,'runtime_digest',lambda:'snapshot')
    return root, manifest


def test_trial_id_cannot_traverse_or_target_unmarked_directory(trial):
    root, _ = trial
    with pytest.raises(M4Error,match='INVALID_TRIAL_ID'): m5.trial_root('../0123456789')
    (root/'.m4-run').unlink()
    with pytest.raises(M4Error,match='RUN_NOT_OWNED'): m5.trial_root(root.name)


@pytest.mark.parametrize('key,value', [('kind','other'),('repository','foreign'),('runId','fffffffffe'),('state','cleaned'),('port',True)])
def test_trial_manifest_must_match_prepared_environment(trial,key,value):
    root, manifest = trial; manifest[key] = value
    (root/'trial.json').write_text(json.dumps(manifest))
    with pytest.raises(M4Error): m5.trial_root(root.name)


def test_trial_launch_uses_owned_environment_uv_and_no_cdp(trial,monkeypatch):
    root, _ = trial; captured = {}
    monkeypatch.setattr(m5.shutil,'which',lambda _:r'C:\tools\uv.exe')
    monkeypatch.setenv('QTWEBENGINE_REMOTE_DEBUGGING','127.0.0.1:9999')
    monkeypatch.setenv('PYTHONPATH','foreign')
    def call(command,**kwargs): captured.update(command=command,**kwargs); return 0
    monkeypatch.setattr(m5.subprocess,'call',call)
    assert m5.launch_trial(root.name) == 0
    command,env = captured['command'], captured['env']
    assert command[0] == r'C:\tools\uv.exe' and '--locked' in command and '--no-sync' in command
    assert '--debug-port' not in command and 'QTWEBENGINE_REMOTE_DEBUGGING' not in env
    assert 'PYTHONPATH' not in env and env['UV_PROJECT_ENVIRONMENT'] == str(root/'work/env')
    assert command[command.index('--profile')+1] == str(root/'work/profile')
    assert command[command.index('--web-root')+1] == str(root/'work/web')


def test_trial_launch_rejects_changed_runtime_or_missing_resources(trial,monkeypatch):
    root,_=trial
    monkeypatch.setattr(m5,'runtime_digest',lambda:'changed')
    with pytest.raises(M4Error,match='RUNTIME_CHANGED'): m5.launch_trial(root.name)
    monkeypatch.setattr(m5,'runtime_digest',lambda:'snapshot')
    (root/'work/web/index.html').unlink()
    with pytest.raises(M4Error,match='RESOURCE_MISSING'): m5.launch_trial(root.name)


def test_trial_port_conflict_does_not_start_or_stop_an_application(trial,monkeypatch):
    root,manifest=trial
    monkeypatch.setattr(m5.shutil,'which',lambda _:'uv')
    monkeypatch.setattr(m5.subprocess,'call',lambda *_a,**_k:pytest.fail('must not spawn'))
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0)); listener.listen()
        manifest['port']=listener.getsockname()[1]; (root/'trial.json').write_text(json.dumps(manifest))
        with pytest.raises(M4Error,match='PORT_UNAVAILABLE'): m5.launch_trial(root.name)


def test_trial_cleanup_refuses_locked_profile_and_leaves_state_unchanged(trial):
    root,manifest=trial
    (root/'work/profile/instance.lock').write_text('synthetic lock')
    with pytest.raises(M4Error,match='PROFILE_LOCK_PRESENT'): m5.cleanup_trial(root.name)
    assert (root/'work').exists() and json.loads((root/'trial.json').read_text()) == manifest


def test_trial_guard_serializes_launch_and_cleanup(trial):
    root,_=trial
    with m5.trial_guard(root):
        with pytest.raises(M4Error,match='TRIAL_IN_USE'): m5.cleanup_trial(root.name)
    assert (root/'work').exists()


def test_trial_cleanup_failure_is_reported_and_preserves_ready_manifest(trial,monkeypatch):
    root,_=trial
    def refuse(*_): raise PermissionError('synthetic')
    monkeypatch.setattr(m5,'remove_owned',refuse)
    with pytest.raises(PermissionError): m5.cleanup_trial(root.name)
    assert json.loads((root/'trial-cleanup.json').read_text())['status'] == 'failed'
    assert json.loads((root/'trial.json').read_text())['state'] == 'ready'


def test_trial_cleanup_preserves_audit_and_unrelated_profiles(trial):
    root,_=trial
    unrelated=root.parent/'daily-profile'; unrelated.mkdir(); (unrelated/'keep').touch()
    assert m5.cleanup_trial(root.name) == 0
    assert not (root/'work').exists() and (unrelated/'keep').exists()
    assert json.loads((root/'trial.json').read_text())['state'] == 'cleaned'
    assert json.loads((root/'trial-cleanup.json').read_text())['status'] == 'passed'


def test_trial_modes_are_mutually_exclusive_and_do_not_prepare_on_launch(trial,monkeypatch):
    root,_=trial
    with pytest.raises(SystemExit) as result: m5.main(['--prepare-trial','--launch-trial',root.name])
    assert result.value.code == 2
    with pytest.raises(SystemExit) as result: m5.main(['--launch-trial',root.name,'--port','5198'])
    assert result.value.code == 2
    monkeypatch.setattr(m5,'launch_trial',lambda identity: 0 if identity == root.name else 1)
    monkeypatch.setattr(m5,'M5Bootstrap',lambda *_:pytest.fail('must not prepare on launch'))
    assert m5.main(['--launch-trial',root.name]) == 0


def test_corrupt_sqlite_fails_without_replacing_bytes(tmp_path):
    import sqlite3
    from engine import Engine
    root=tmp_path/'native'; root.mkdir()
    database=root/'desktop.sqlite3'; original=b'M5 corrupt SQLite fixture'; database.write_bytes(original)
    with pytest.raises(sqlite3.DatabaseError): Engine(root)
    assert database.read_bytes() == original


def test_prepare_trial_retains_empty_profile_without_starting_live_accounts(trial,monkeypatch):
    root, _ = trial
    (root/'work/profile/.bcd-qt-desktop').unlink(); (root/'work/profile').rmdir()
    (root/'work/pytest').mkdir()
    run = object.__new__(m5.M5Bootstrap)
    run.out, run.work, run.id = root, root/'work', root.name
    run.base, run.port, run.checks = 'http://127.0.0.1:8080/api/v1', 5199, []
    run.prepare = lambda: None
    run.run_live = lambda *_a,**_k: pytest.fail('trial preparation must not create live accounts or windows')
    assert run.run(prepare_trial=True) == 0
    report=json.loads((root/'results.json').read_text())
    manifest=json.loads((root/'trial.json').read_text())
    assert report['retainedResources'] and report['manualObservation'] == 'not-executed'
    assert manifest['accountsCreated'] == 0 and manifest['cdpEnabled'] is False
    assert list((root/'work/profile').iterdir()) == [root/'work/profile/.bcd-qt-desktop']
    assert not (root/'work/pytest').exists()


def test_m5_required_execution_or_cleanup_failure_returns_nonzero(trial,monkeypatch):
    root,_ = trial
    run=object.__new__(m5.M5Bootstrap)
    run.out,run.work,run.id,run.checks=root,root/'work',root.name,[]
    def failed_prepare(): raise M4Error('SYNTHETIC_REQUIRED_FAILURE')
    run.prepare=failed_prepare
    def failed_cleanup(*_): raise PermissionError('synthetic')
    monkeypatch.setattr(m5,'remove_owned',failed_cleanup)
    assert run.run() == 1
    report=json.loads((root/'results.json').read_text())
    assert report['status'] == 'failed' and not report['retainedResources']
    assert next(c for c in report['checks'] if c['name']=='isolated-environment-cleanup')['status'] == 'failed'
