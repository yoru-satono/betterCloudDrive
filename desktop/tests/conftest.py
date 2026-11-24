"""Unit/media checks must not open windows or take keyboard focus."""
import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
