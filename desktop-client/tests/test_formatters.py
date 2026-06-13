from betterclouddrive_desktop.services.formatters import format_date, format_size


def test_format_size():
    assert format_size(0) == "0 B"
    assert format_size(1024) == "1 KB"
    assert format_size(1536) == "1.5 KB"
    assert format_size(1024 * 1024 * 1024) == "1 GB"


def test_format_date():
    assert format_date(None) == "-"
    assert format_date("bad") == "bad"
    assert format_date("2026-01-02T03:04:05") == "2026-01-02 03:04"

