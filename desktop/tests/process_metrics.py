"""Cross-platform acceptance process sampling; never targets unrelated processes."""
import os
import psutil


def select_tree(parents, values, root):
    """Windows retains parent IDs after exit; a reused ID is not ancestry."""
    if root not in parents or root not in values: return {}
    members = {root}
    while True:
        expanded = members | {pid for pid, parent in parents.items()
                              if parent in members and pid in values
                              and values[pid]['created'] >= values[parent]['created']}
        if expanded == members: break
        members = expanded
    return {pid: values[pid] for pid in members}


def sample_processes(pids=None, root=None):
    root = root or os.getpid()
    parents, metadata = {}, {}
    for process in psutil.process_iter(['pid', 'ppid', 'create_time', 'status'], ad_value=None):
        info = process.info
        if info['status'] in (psutil.STATUS_ZOMBIE, psutil.STATUS_DEAD) or info['create_time'] is None:
            continue
        parents[process.pid] = info['ppid']
        metadata[process.pid] = {'created': info['create_time']}
    if pids is None:
        selected = select_tree(parents, metadata, root)
    else:
        selected = {pid:metadata[pid] for pid in pids if pid in metadata}
    values = {}
    for pid, expected in selected.items():
        try:
            process = psutil.Process(pid)
            if process.create_time() != expected['created'] or not process.is_running(): continue
            if process.status() in (psutil.STATUS_ZOMBIE, psutil.STATUS_DEAD): continue
            cpu = process.cpu_times()
            values[pid] = {'created': expected['created'], 'cpu': cpu.user+cpu.system, 'rss': process.memory_info().rss}
        except (psutil.NoSuchProcess, psutil.ZombieProcess):
            continue
        except psutil.AccessDenied:
            raise RuntimeError('OWNED_PROCESS_UNREADABLE') from None
    return values


def sample_tree(root=None):
    return sample_processes(root=root)


def terminate_owned(owned):
    for pid, created in owned.items():
        try:
            process = psutil.Process(pid)
            if process.create_time() == created and process.is_running():
                process.kill()  # psutil also checks PID reuse before termination.
        except (psutil.NoSuchProcess, psutil.ZombieProcess):
            continue
        except psutil.AccessDenied:
            raise RuntimeError('OWNED_PROCESS_TERMINATION_DENIED') from None
