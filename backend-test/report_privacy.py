"""Redact credentials before pytest renders exceptions or writes captured report sections."""

import re
import logging

BEARER = re.compile(r"(?i)\bBearer\s+[^\s'\"<>,}\]]+")
JWT = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
SECRET_FIELD = re.compile(
    r'''(?ix)(['"]?(?:password|authorization|cookie|set-cookie|accessToken|refreshToken|adminToken|token|ticket|verificationCode)['"]?\s*[:=]\s*)
    (?P<quote>['"])(?:\\.|(?!(?P=quote)).)*(?P=quote)'''
)
SECRET_QUERY = re.compile(r"(?i)([?&](?:token|ticket|access_token|refresh_token|signature)=)[^&\s'\"]+")


def redact(value: str) -> str:
    value = SECRET_FIELD.sub(lambda match: match.group(1) + '"<redacted>"', value)
    value = BEARER.sub("Bearer <redacted>", value)
    value = JWT.sub("<redacted>", value)
    return SECRET_QUERY.sub(lambda match: match.group(1) + "<redacted>", value)


class RedactingFilter(logging.Filter):
    def filter(self, record):
        record.msg = redact(record.getMessage())
        record.args = ()
        return True
