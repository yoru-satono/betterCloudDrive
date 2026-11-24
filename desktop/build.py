"""Build the existing Vue application with desktop-only entrypoints."""
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
subprocess.run(["pnpm.cmd" if os.name == "nt" else "pnpm", "exec", "vite", "build",
                "--config", str(ROOT / "vite.config.mjs")], cwd=ROOT.parent / "frontend", check=True)
