"""Build the existing Vue application with desktop-only entrypoints."""
import os
import argparse
import subprocess
import sys
from pathlib import Path

from m4_support import M4Error, owned_root, require

ROOT = Path(__file__).resolve().parent


def owned_output(value):
    target = Path(value).absolute()
    runs = (ROOT.parent / 'test-results/desktop').absolute()
    run = next((parent for parent in target.parents if parent.parent == runs), None)
    require(run is not None, 'BUILD_OUTPUT_NOT_OWNED')
    run = owned_root(run)
    require(target != run / 'work' and target.is_relative_to(run / 'work'), 'BUILD_OUTPUT_NOT_IN_WORK')
    for item in (target, *target.parents):
        if item == run: break
        require(not item.is_symlink() and not item.is_junction(), 'UNSAFE_BUILD_OUTPUT')
    require(target.resolve() != run / 'work' and target.resolve().is_relative_to(run / 'work'), 'UNSAFE_BUILD_OUTPUT')
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out-dir', type=Path, help='Optional output inside a marker-owned verification run/work directory')
    args = parser.parse_args(argv)
    command = ['pnpm.cmd' if os.name == 'nt' else 'pnpm', 'exec', 'vite', 'build', '--config', str(ROOT / 'vite.config.mjs')]
    try:
        if args.out_dir:
            command += ['--outDir', str(owned_output(args.out_dir)), '--emptyOutDir']
        return subprocess.run(command, cwd=ROOT.parent / 'frontend').returncode
    except M4Error as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
