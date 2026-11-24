"""Run an acceptance worker in the prepared environment; parent stays stdlib-only."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from m4_support import require, owned_root, process_fingerprints, surviving_owned
from process_metrics import sample_tree, sample_processes, terminate_owned


def main():
    target=Path(sys.argv[1]).resolve()
    require(target.parent==Path(__file__).resolve().parent and target.name in ('m4_live.py','m5_live.py'),'INVALID_MONITORED_WORKER')
    config=json.loads(sys.stdin.readline());owned_root(config['root'])
    if hasattr(signal,'SIGBREAK'): signal.signal(signal.SIGBREAK,signal.default_int_handler)
    process=subprocess.Popen([sys.executable,str(target)],cwd=ROOT,stdin=subprocess.PIPE,text=True,encoding='utf-8')
    owned={}
    try:
        process.stdin.write(json.dumps(config)+'\n');process.stdin.close()
        deadline=time.monotonic()+900
        while process.poll() is None:
            owned.update(process_fingerprints(sample_tree(process.pid)))
            require(time.monotonic()<deadline,'LIVE_RUN_TIMEOUT')
            time.sleep(.2)
        return process.returncode
    finally:
        if process.poll() is None:
            terminate_owned(owned);process.wait(timeout=10)
        remaining=surviving_owned(owned,sample_processes(owned))
        if remaining:
            terminate_owned({pid:owned[pid] for pid in remaining})
            raise RuntimeError('LIVE_CHILD_PROCESS_RETAINED')


if __name__=='__main__': sys.exit(main())
