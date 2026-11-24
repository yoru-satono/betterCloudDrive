"""Build PoC web assets and an independent Windows directory distribution."""
import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--web-only", action="store_true")
args = parser.parse_args()
frontend = ROOT.parents[1] / "frontend"
pnpm = "pnpm.cmd" if os.name == "nt" else "pnpm"
subprocess.run([pnpm, "exec", "vite", "build", "--config", str(ROOT / "vite.config.mjs")], cwd=frontend, check=True)
if not args.web_only:
    # DLL analysis must not pick an unrelated ICU/OpenSSL from Conda, Poppler,
    # or the host app's augmented PATH. Qt uses Windows' system ICU on this host.
    pack_env = dict(os.environ)
    if os.name == "nt":
        windows = Path(os.environ["SystemRoot"])
        pack_env["PATH"] = os.pathsep.join(map(str, [Path(sys.executable).parent, windows / "System32", windows]))
    for key in ("PYTHONPATH", "QT_PLUGIN_PATH", "QML2_IMPORT_PATH", "CONDA_PREFIX"):
        pack_env.pop(key, None)
    subprocess.run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--windowed", "--onedir",
                    "--clean",
                    "--name", "BetterCloudDriveQtPoc", "--add-data", f"{ROOT / 'web'}{os.pathsep}web",
                    "--distpath", str(ROOT / "dist"), "--workpath", str(ROOT / "build"),
                    "--specpath", str(ROOT), str(ROOT / "app.py")], cwd=ROOT, env=pack_env, check=True)
