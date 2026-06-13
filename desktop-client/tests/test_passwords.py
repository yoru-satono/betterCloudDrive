import pytest

from betterclouddrive_desktop.services.passwords import generate_share_password, validate_share_password


def test_generate_share_password_default_and_eight_chars():
    assert len(generate_share_password()) == 4
    assert len(generate_share_password(8)) == 8


def test_share_password_length_validation():
    validate_share_password("1234")
    validate_share_password("a" * 16)
    with pytest.raises(ValueError):
        validate_share_password("123")
    with pytest.raises(ValueError):
        validate_share_password("a" * 17)

