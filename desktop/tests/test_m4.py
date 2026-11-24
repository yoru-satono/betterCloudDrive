import json
from pathlib import Path
import socket
from types import SimpleNamespace

import pytest

from m4_support import M4Error, check_ports, clean_environment, remove_owned, process_fingerprints, surviving_owned


def run_root(tmp_path):
    root = tmp_path / '0123456789'; root.mkdir()
    (root / '.m4-run').write_text(root.name)
    return root


def test_m4_environment_removes_injected_runtime_paths_without_changing_parent():
    source = {'Path': 'untrusted', 'PYTHONPATH': 'foreign', 'PYTHONHOME': 'foreign', 'QT_PLUGIN_PATH': 'foreign',
              'QTWEBENGINE_CHROMIUM_FLAGS': '--remote-debugging-port=99', 'QTWEBENGINEPROCESS_PATH': 'foreign',
              'QTDIR': 'foreign', 'QML2_IMPORT_PATH': 'foreign',
              'FFMPEG_DIR': 'foreign', 'VIRTUAL_ENV': 'daily', 'UV_PROJECT_ENVIRONMENT': 'daily', 'SystemRoot': r'C:\Windows'}
    env = clean_environment(source, [Path(r'C:\tools')])
    assert source['PYTHONPATH'] == 'foreign' and source['VIRTUAL_ENV'] == 'daily'
    assert not any(key in env for key in ('Path', 'PYTHONPATH', 'PYTHONHOME', 'QT_PLUGIN_PATH', 'QTWEBENGINE_CHROMIUM_FLAGS', 'QTWEBENGINEPROCESS_PATH', 'QTDIR', 'QML2_IMPORT_PATH', 'FFMPEG_DIR', 'VIRTUAL_ENV', 'UV_PROJECT_ENVIRONMENT'))
    assert 'untrusted' not in env['PATH'] and env['QT_MEDIA_BACKEND'] == 'ffmpeg'


def test_m4_refuses_occupied_or_same_ports_before_starting_any_process():
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1', 0)); occupied.listen()
        port = occupied.getsockname()[1]
        with pytest.raises(M4Error, match='TEST_PORT_UNAVAILABLE'): check_ports(port, 9230 if port != 9230 else 9231)
    with pytest.raises(M4Error, match='INVALID_TEST_PORTS'): check_ports(5198,5198)


def test_m4_cleanup_rejects_unowned_or_outside_paths_and_preserves_reports(tmp_path):
    root = run_root(tmp_path); work = root / 'work'; work.mkdir(); (work/'cache').write_text('fixture')
    report = root/'results.json'; report.write_text('{}')
    outside = tmp_path/'unrelated'; outside.mkdir()
    with pytest.raises(M4Error, match='CLEANUP_OUTSIDE_RUN'): remove_owned(root, outside)
    (root/'.m4-run').write_text('wrong')
    with pytest.raises(M4Error, match='RUN_NOT_OWNED'): remove_owned(root, work)
    (root/'.m4-run').write_text(root.name)
    remove_owned(root, work)
    assert report.exists() and outside.exists() and not work.exists()


def test_m4_cleanup_failure_propagates_instead_of_counting_as_pass(tmp_path, monkeypatch):
    root = run_root(tmp_path); work = root/'work'; work.mkdir()
    monkeypatch.setattr('m4_support.shutil.rmtree', lambda *_: (_ for _ in ()).throw(PermissionError('synthetic')))
    with pytest.raises(PermissionError): remove_owned(root, work)
    assert work.exists()


def test_m4_cleanup_unlinks_internal_uv_alias_but_rejects_external_junction(tmp_path):
    import os, subprocess
    root = run_root(tmp_path); work = root/'work'; work.mkdir(); actual = work/'python'; actual.mkdir()
    (actual/'runtime').write_text('fixture')
    outside = tmp_path/'outside'; outside.mkdir(); (outside/'keep').write_text('unrelated')
    def junction(link, target):
        if os.name == 'nt':
            subprocess.run(['cmd','/c','mklink','/J',str(link),str(target)], check=True, capture_output=True)
        else: link.symlink_to(target, target_is_directory=True)
    junction(work/'alias', actual); junction(work/'external', outside)
    with pytest.raises(M4Error, match='UNSAFE_CLEANUP_PATH'): remove_owned(root,work)
    assert (actual/'runtime').exists() and (outside/'keep').exists()
    if os.name == 'nt': os.rmdir(work/'external')
    else: (work/'external').unlink()
    remove_owned(root,work)
    assert not work.exists() and (outside/'keep').exists()


def test_m4_process_ownership_checks_creation_time_and_ignores_reused_pids():
    owned = process_fingerprints({10:{'created':100,'rss':1},20:{'created':200,'rss':1}})
    current = {10:{'created':101},20:{'created':200},30:{'created':300}}
    assert surviving_owned(owned,current) == [20]


@pytest.mark.skipif(__import__('os').name != 'nt', reason='Windows process handles')
def test_m4_exited_process_is_not_reported_alive_while_popen_keeps_its_handle():
    import subprocess, sys
    from process_metrics import sample_processes
    process = subprocess.Popen([sys.executable, '-c', 'pass'], creationflags=subprocess.CREATE_NO_WINDOW)
    process.wait(timeout=10)
    assert process.pid not in sample_processes([process.pid])


def test_acceptance_launcher_uses_requested_interpreter_resources_and_environment(tmp_path, monkeypatch):
    from validate import Validation
    run = object.__new__(Validation)
    run.port, run.debug_port, run.offscreen = 5198, 9230, True
    run.out = tmp_path; run.api = SimpleNamespace(base='http://127.0.0.1:8080/api/v1')
    run.python_executable, run.web_root = tmp_path/'fresh/python.exe', tmp_path/'web'
    run.process_env = {'PATH':'controlled','CUSTOM':'isolated'}
    captured = {}
    def popen(command, **kwargs): captured.update(command=command, **kwargs); return object()
    class Page:
        def __init__(self, *args, **kwargs): pass
        def wait(self,*args): captured['ready'] = args[0]
        def invoke(self,*args): pass
        def evaluate(self,*args): pass
    monkeypatch.setattr('validate.subprocess.Popen', popen)
    monkeypatch.setattr('validate.Cdp', Page)
    run.launch(expect_login=False, ready_expression='authenticatedEmptyList')
    assert captured['command'][0] == str(run.python_executable)
    assert captured['command'][-2:] == ['--web-root', str(run.web_root)]
    assert captured['env']['CUSTOM'] == 'isolated' and captured['env']['QT_QPA_PLATFORM'] == 'offscreen'
    assert captured['ready'] == 'authenticatedEmptyList'
