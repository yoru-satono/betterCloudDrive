import pytest
import logging

from report_privacy import RedactingFilter, redact


@pytest.mark.parametrize("text", [
    "headers = {'Authorization': 'Bearer synthetic-secret'}",
    "user = {'password': 'synthetic-secret', 'refreshToken': 'synthetic-secret'}",
    "GET /download?ticket=synthetic-secret&mode=file",
    "Authorization: Bearer synthetic-secret",
], ids=["bearer-header", "mapped-secrets", "ticket-query", "raw-bearer"])
def test_report_redacts_credentials_without_losing_failure_context(text):
    result = redact("AssertionError: expected code 200\n" + text)
    assert "synthetic-secret" not in result
    assert "AssertionError: expected code 200" in result
    assert "<redacted>" in result


def test_transport_filter_keeps_method_path_and_status_without_ticket_values():
    record = logging.LogRecord("httpx", logging.INFO, __file__, 1,
                               'HTTP Request: GET %s "HTTP/1.1 200 OK"',
                               ("http://localhost/download?ticket=synthetic-secret&mode=file",), None)
    assert RedactingFilter().filter(record) is True
    assert "synthetic-secret" not in record.getMessage()
    assert 'GET http://localhost/download?ticket=<redacted>&mode=file "HTTP/1.1 200 OK"' in record.getMessage()
