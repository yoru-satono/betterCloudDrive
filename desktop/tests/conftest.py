"""Unit/media checks must not open windows or take keyboard focus."""
import os
import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")


def pytest_configure(config):
    config.addinivalue_line('markers', 'windows_only: Windows-specific acceptance behavior')


def pytest_collection_modifyitems(items):
    if os.name != 'nt':
        for item in items:
            if item.get_closest_marker('windows_only'):
                item.add_marker(pytest.mark.skip(reason='PLATFORM_NOT_APPLICABLE:windows'))
