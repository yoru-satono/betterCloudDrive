#!/bin/bash
# Wait for backend to become healthy
URL="${BACKEND_URL:-http://localhost:8080}/api/v1/auth/register"
TIMEOUT=${1:-60}
INTERVAL=2
ELAPSED=0

echo "Waiting for backend at ${URL}..."
while [ $ELAPSED -lt $TIMEOUT ]; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$URL" \
        -H "Content-Type: application/json" \
        -d '{"username":"healthcheck","password":"HealthCheck1"}' 2>/dev/null)
    if [ "$STATUS" != "000" ]; then
        echo "Backend is up (status: $STATUS, took ${ELAPSED}s)"
        exit 0
    fi
    sleep $INTERVAL
    ELAPSED=$((ELAPSED + INTERVAL))
done

echo "Backend did not become ready within ${TIMEOUT}s"
exit 1
