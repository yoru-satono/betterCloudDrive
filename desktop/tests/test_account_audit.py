import json
import pytest
import live_support
from network import DesktopError

def test_native_account_audit_is_scoped_and_contains_only_public_metadata(tmp_path, monkeypatch):
    monkeypatch.setattr(live_support, 'AUDIT_ROOT', tmp_path)
    run = '0123456789'; root = tmp_path/run; root.mkdir()
    username = 'e2e_qtd_'+run+'_owner'
    live_support.audit_account(run, 17, username, username+'@test.local')
    assert json.loads((root/'account-audit.jsonl').read_text()) == {'userId':17, 'username':username, 'email':username+'@test.local'}
    with pytest.raises(DesktopError, match='SCOPE_DENIED'):
        live_support.audit_account(run, 18, 'unrelated-account', 'unrelated@test.local')
    with pytest.raises(DesktopError, match='SCOPE_DENIED'):
        live_support.audit_account('../foreign', 18, username, username+'@test.local')
