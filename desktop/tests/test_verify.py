import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

import build
import verify
from m4_support import M4Error


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    desktop = tmp_path/'repo/desktop'; desktop.mkdir(parents=True)
    (desktop/'web-dist').mkdir(); (desktop/'web-dist/index.html').write_text('daily assets')
    monkeypatch.setattr(build, 'ROOT', desktop)
    monkeypatch.setattr(verify, 'ROOT', desktop)
    monkeypatch.setattr(verify.shutil, 'which', lambda name: str(tmp_path/'tools'/name))
    return desktop


def owned_build(workspace):
    root=workspace.parent/'test-results/desktop/0123456789'; root.mkdir(parents=True)
    (root/'.m4-run').write_text(root.name)
    (root/'work').mkdir()
    return root, root/'work/web'


def test_default_build_retains_existing_command_and_custom_build_passes_owned_target(workspace,monkeypatch):
    captured=[]
    monkeypatch.setattr(build.subprocess,'run',lambda command,**kwargs: captured.append((command,kwargs)) or SimpleNamespace(returncode=0))
    assert build.main([]) == 0
    assert '--outDir' not in captured[-1][0] and captured[-1][1]['cwd'] == workspace.parent/'frontend'
    _,target=owned_build(workspace)
    assert build.main(['--out-dir',str(target)]) == 0
    command=captured[-1][0]
    assert command[command.index('--outDir')+1] == str(target) and '--emptyOutDir' in command


@pytest.mark.parametrize('target', ['outside','daily','work','escape','work-via-parent'])
def test_custom_build_rejects_unowned_or_unsafe_targets_without_spawning(workspace,monkeypatch,target):
    root,web=owned_build(workspace)
    choices={'outside':workspace.parent/'other','daily':workspace/'web-dist', 'work':root/'work',
             'escape':root/'work/../report', 'work-via-parent':root/'work/web/..'}
    monkeypatch.setattr(build.subprocess,'run',lambda *_a,**_k:pytest.fail('unsafe output must not build'))
    assert build.main(['--out-dir',str(choices[target])]) == 1
    assert (workspace/'web-dist/index.html').read_text() == 'daily assets'


def test_custom_build_rejects_wrong_marker_and_external_junction(workspace):
    root,web=owned_build(workspace)
    (root/'.m4-run').write_text('wrong')
    with pytest.raises(M4Error,match='RUN_NOT_OWNED'): build.owned_output(web)
    (root/'.m4-run').write_text(root.name)
    outside=workspace.parent/'unrelated'; outside.mkdir()
    link=root/'work/alias'
    if os.name == 'nt':
        import subprocess
        subprocess.run(['cmd','/c','mklink','/J',str(link),str(outside)],check=True,capture_output=True)
    else: link.symlink_to(outside,target_is_directory=True)
    try:
        with pytest.raises(M4Error,match='UNSAFE_BUILD_OUTPUT'): build.owned_output(link/'web')
        assert outside.exists()
    finally:
        if os.name == 'nt': os.rmdir(link)
        else: link.unlink()


def test_verification_child_environment_is_owned_and_parent_remains_unchanged(workspace,monkeypatch):
    monkeypatch.setenv('PYTHONPATH','foreign')
    monkeypatch.setenv('QT_PLUGIN_PATH','foreign')
    monkeypatch.setenv('UV_PROJECT_ENVIRONMENT','trial-environment')
    before=dict(os.environ)
    run=verify.Verification()
    unchanged = dict(os.environ) == before
    assert unchanged
    assert 'PYTHONPATH' not in run.env and 'QT_PLUGIN_PATH' not in run.env
    assert run.env['UV_PROJECT_ENVIRONMENT'] == str(workspace/'.venv')
    assert Path(run.env['TEMP']).is_relative_to(run.work) and run.env['TMP'] == run.env['TEMP']
    assert run.env['QT_QPA_PLATFORM'] == 'offscreen'


def mock_commands(run, fail=None):
    commands=[]
    def command(name, args, cwd=None):
        commands.append((name,args))
        if name == fail: raise M4Error('SYNTHETIC_REQUIRED_FAILURE')
        run.checks.append({'name':name,'status':'passed'})
    run.command=command
    return commands


def test_verification_success_uses_isolated_build_and_cleans_work(workspace):
    before=verify.web_fingerprint(workspace/'web-dist')
    run=verify.Verification(); commands=mock_commands(run)
    assert run.run() == 0
    report=json.loads((run.out/'results.json').read_text())
    assert report['status'] == 'passed' and not run.work.exists()
    assert before == verify.web_fingerprint(workspace/'web-dist')
    build_args=next(args for name,args in commands if name=='isolated-web-build')
    assert build_args[-2:] == ['--out-dir',run.work/'web']
    pytest_args=next(args for name,args in commands if name=='python-regression')
    assert pytest_args[pytest_args.index('--basetemp')+1] == run.work/'pytest'


def test_verification_failure_stops_later_steps_and_still_cleans(workspace):
    run=verify.Verification(); commands=mock_commands(run,'python-regression')
    assert run.run() == 1
    assert not run.work.exists() and all(name!='isolated-web-build' for name,_ in commands)
    assert json.loads((run.out/'results.json').read_text())['status'] == 'failed'


def test_cleanup_failure_is_nonzero_and_keeps_report(workspace,monkeypatch):
    run=verify.Verification(); mock_commands(run)
    def fail(*_): raise PermissionError('synthetic')
    monkeypatch.setattr(verify,'remove_owned',fail)
    assert run.run() == 1 and run.work.exists()
    report=json.loads((run.out/'results.json').read_text())
    assert report['status']=='failed' and report['checks'][-1]['name']=='run-resources-cleaned'
    assert report['checks'][-1]['status']=='failed'


def test_changed_daily_assets_are_a_failure_even_if_checks_pass(workspace):
    run=verify.Verification()
    command=mock_commands(run)
    previous=run.command
    def change(name,args,cwd=None):
        previous(name,args,cwd)
        if name=='isolated-web-build': (workspace/'web-dist/index.html').write_text('unexpected change')
    run.command=change
    assert run.run()==1 and not run.work.exists()
    report=json.loads((run.out/'results.json').read_text())
    assert next(c for c in report['checks'] if c['name']=='daily-web-preserved')['status']=='failed'


def test_failed_child_output_never_prints_credentials(workspace,capsys):
    import sys
    run=verify.Verification()
    with pytest.raises(M4Error,match='SYNTHETIC_CHECK_FAILED'):
        run.command('synthetic-check',[sys.executable,'-c',"print('opaque-synthetic-credential');raise SystemExit(7)"])
    assert 'opaque-synthetic-credential' not in capsys.readouterr().out
    assert run.checks[-1]['exitCode']==7 and run.checks[-1]['ownedChildrenExited']


def test_process_tree_rejects_older_children_of_reused_parent_ids():
    from process_metrics import select_tree
    parents={10:0,20:10,30:20,40:10,50:40}
    values={pid:{'created':created} for pid,created in ((10,200),(20,100),(30,210),(40,220),(50,230))}
    assert set(select_tree(parents,values,10)) == {10,40,50}
    assert not select_tree({20:10},values,10)


def test_posix_environment_uses_posix_tools_and_never_windows_system_paths():
    from m4_support import clean_environment
    env=clean_environment({'PATH':'foreign','PYTHONPATH':'foreign','SystemRoot':r'C:\Windows',
                           'LD_LIBRARY_PATH':'foreign','DYLD_LIBRARY_PATH':'foreign'},['/opt/tools'],platform_name='posix')
    assert env['PATH'].startswith('/opt/tools:') and '/usr/bin' in env['PATH']
    assert 'Windows' not in env['PATH'] and 'PYTHONPATH' not in env
    assert not any(k in env for k in ('LD_LIBRARY_PATH','DYLD_LIBRARY_PATH'))


def test_portable_sampler_tracks_real_children_and_excludes_exited_handles():
    import subprocess, sys
    from process_metrics import sample_tree, sample_processes
    child=subprocess.Popen([sys.executable,'-c',"print('ready',flush=True);input()"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    try:
        assert child.stdout.readline().strip()=='ready'
        assert child.pid in sample_tree(os.getpid())
    finally: child.communicate('\n',timeout=10)
    assert child.pid not in sample_processes([child.pid])


def test_internal_worker_and_build_cannot_take_over_a_retained_trial(workspace):
    root,web=owned_build(workspace)
    (root/'trial.json').write_text('{"kind":"m5-trial"}')
    with pytest.raises(M4Error,match='NOT_A_QT_VERIFICATION_RUN'): verify.Verification(existing_root=root)
    with pytest.raises(M4Error,match='TRIAL_OUTPUT_IS_RETAINED'): build.owned_output(web)
    assert (root/'work').exists()
