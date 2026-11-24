"""Standard-library-only isolation and ownership helpers for M4 acceptance."""
import os
from pathlib import Path
import re
import shutil
import socket


class M4Error(Exception):
    pass


def require(value, code):
    if not value:
        raise M4Error(code)


def check_ports(port, debug_port):
    require(port != debug_port and all(1 <= p <= 65535 for p in (port, debug_port)), 'INVALID_TEST_PORTS')
    for number in (port, debug_port):
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', number))
            except OSError:
                raise M4Error('TEST_PORT_UNAVAILABLE') from None


def owned_root(root):
    root = Path(root).absolute()
    require(re.fullmatch(r'[0-9a-f]{10}', root.name), 'INVALID_RUN_ID')
    for item in (root, *root.parents):
        require(not item.is_symlink() and not item.is_junction(), 'UNSAFE_RUN_PATH')
    marker = root / '.m4-run'
    require(marker.is_file() and marker.read_text(encoding='utf-8') == root.name, 'RUN_NOT_OWNED')
    return root.resolve()


def remove_owned(root, target):
    root = owned_root(root)
    target = Path(target).absolute()
    require(target != root and target.resolve().is_relative_to(root), 'CLEANUP_OUTSIDE_RUN')
    for item in (target, *target.parents):
        if item == root:
            break
        require(not item.is_symlink() and not item.is_junction(), 'UNSAFE_CLEANUP_PATH')
    if target.is_dir():
        # uv creates a managed-Python version alias as an internal junction on
        # Windows. Validate its destination, unlink the alias itself, and never
        # traverse it. Reject links to anything outside this owned run.
        aliases = []
        def inspect(directory):
            for item in directory.iterdir():
                if item.is_symlink() or item.is_junction():
                    require(item.resolve().is_relative_to(root), 'UNSAFE_CLEANUP_PATH')
                    aliases.append(item)
                elif item.is_dir(): inspect(item)
        inspect(target)
        for item in aliases:
            if item.is_junction(): os.rmdir(item)
            else: item.unlink()
        shutil.rmtree(target)
    elif target.exists():
        target.unlink()


def clean_environment(source, tool_dirs, platform_name=None):
    blocked = ('PYTHON', 'QT', 'QML', 'FFMPEG', 'UV_', 'DYLD_')
    excluded = {'PATH', 'VIRTUAL_ENV', 'CONDA_PREFIX', 'CONDA_DEFAULT_ENV', '__PYVENV_LAUNCHER__', 'LD_LIBRARY_PATH'}
    env = {k: v for k, v in source.items() if k.upper() not in excluded and not k.upper().startswith(blocked)}
    kind = platform_name or os.name
    if kind == 'nt':
        system = Path(source.get('SystemRoot', r'C:\Windows'))
        directories = [system / 'System32', system, system / 'System32/Wbem']
    else:
        directories = ['/usr/local/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin']
    env['PATH'] = (';' if kind == 'nt' else ':').join(dict.fromkeys(str(p) for p in [*tool_dirs, *directories]))
    env.update(PYTHONUTF8='1', QT_MEDIA_BACKEND='ffmpeg', UV_PYTHON_INSTALL_BIN='0')
    return env


def process_fingerprints(values):
    return {pid: value['created'] for pid, value in values.items()}


def surviving_owned(owned, current):
    return [pid for pid, created in owned.items() if pid in current and current[pid]['created'] == created]
