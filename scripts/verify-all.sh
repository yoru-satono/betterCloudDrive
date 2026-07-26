#!/usr/bin/env bash

set -uo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/verify-all.sh [options]

Options:
  -m, --module NAME          Module to run; repeatable (default: all)
                            all, backend, blackbox, web, tauri, android-unit, android,
                            integration, security, office, webdav, observability, resilience
      --skip-docker-build   Start Compose without rebuilding images
      --skip-tauri          Skip Tauri (not allowed with --module all)
      --skip-android        Skip Android (not allowed with --module all)
      --skip-onlyoffice     Skip OnlyOffice (not allowed with --module all)
      --skip-e2e-cleanup    Do not remove this run's E2E data
      --android-serial ID   adb device serial (default: localhost:16416)
      --keep-going          Continue after a failed module
  -h, --help                Show this help
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

modules=()
skip_docker_build=0
skip_tauri=0
skip_android=0
skip_onlyoffice=0
skip_e2e_cleanup=0
android_serial="localhost:16416"
keep_going=0

while (($#)); do
  case "$1" in
    -m|--module)
      (($# >= 2)) || die "$1 requires a module name"
      modules+=("${2,,}")
      shift 2
      ;;
    --skip-docker-build) skip_docker_build=1; shift ;;
    --skip-tauri) skip_tauri=1; shift ;;
    --skip-android) skip_android=1; shift ;;
    --skip-onlyoffice) skip_onlyoffice=1; shift ;;
    --skip-e2e-cleanup) skip_e2e_cleanup=1; shift ;;
    --android-serial)
      (($# >= 2)) || die "$1 requires a serial"
      android_serial="$2"
      shift 2
      ;;
    --keep-going) keep_going=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

((${#modules[@]})) || modules=(all)

declare -A valid_modules=(
  [all]=1 [backend]=1 [blackbox]=1 [web]=1 [tauri]=1 [android-unit]=1 [android]=1
  [integration]=1 [security]=1 [office]=1 [webdav]=1 [observability]=1 [resilience]=1
)
declare -A selected=()
for module in "${modules[@]}"; do
  [[ ${valid_modules[$module]+yes} ]] || die "unknown module: $module"
  selected["$module"]=1
done

has_module() { [[ ${selected[$1]+yes} ]]; }

run_all=0; has_module all && run_all=1
run_backend=0; { ((run_all)) || has_module backend; } && run_backend=1
run_integration=0; { ((run_all)) || has_module integration; } && run_integration=1
run_blackbox=0; { ((run_all)) || has_module blackbox; } && run_blackbox=1
run_security=0; { ((run_all)) || has_module security; } && run_security=1
run_web=0; { ((run_all)) || has_module web; } && run_web=1
run_tauri=0; { ((run_all)) || has_module tauri; } && ((!skip_tauri)) && run_tauri=1
run_android=0; { ((run_all)) || has_module android; } && ((!skip_android)) && run_android=1
run_android_unit=$run_android; has_module android-unit && run_android_unit=1
run_office=0; { ((run_all)) || has_module office; } && ((!skip_onlyoffice)) && run_office=1
run_webdav=0; { ((run_all)) || has_module webdav; } && run_webdav=1
run_observability=0; { ((run_all)) || has_module observability; } && run_observability=1
run_resilience=0; { ((run_all)) || has_module resilience; } && run_resilience=1

needs_backend_http=$((run_blackbox || run_security || run_web || run_tauri || run_android || run_office || run_webdav || run_observability || run_resilience))
needs_frontend_http=$((run_web || run_tauri || run_office))
needs_mailpit=$((run_blackbox || run_web || run_tauri || run_android || run_office))
needs_onlyoffice=$run_office
needs_docker=$((needs_backend_http || needs_onlyoffice))
should_cleanup=$((!skip_e2e_cleanup && needs_docker))

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root" || exit 2

run_id="${BCD_TEST_RUN_ID:-bcd_$(date +%Y%m%d_%H%M%S)_$$}"
export BCD_TEST_RUN_ID="$run_id"
result_root="$repo_root/test-results"
mkdir -p "$result_root"/{backend,integration,blackbox,security,web,tauri,android,office,webdav,observability,resilience,coverage}

if ((run_all && (skip_tauri || skip_android || skip_onlyoffice))); then
  die "module 'all' is a complete acceptance run; skip flags for Tauri, Android, and OnlyOffice are not allowed"
fi

command -v docker >/dev/null || ((needs_docker == 0)) || die "docker was not found on PATH"
command -v rclone >/dev/null || ((run_webdav == 0)) || die "rclone is required by the webdav module"
command -v adb >/dev/null || ((run_android == 0)) || die "adb is required by the android module"

declare -a result_names=() result_statuses=() result_seconds=() result_messages=()
had_failure=0
stack_started=0

run_step() {
  local name=$1
  shift
  local started ended status message rc
  printf '\n==> %s\n' "$name"
  started=$(date +%s)
  "$@"
  rc=$?
  ended=$(date +%s)
  if ((rc == 0)); then
    status=PASS
    message=""
  else
    status=FAIL
    message="exit code $rc"
    had_failure=1
  fi
  result_names+=("$name")
  result_statuses+=("$status")
  result_seconds+=("$((ended - started))")
  result_messages+=("$message")
  printf '<== %s %s (%ss)\n' "$status" "$name" "$((ended - started))"
  if ((rc != 0 && !keep_going)); then
    return "$rc"
  fi
  return 0
}

copy_if_missing() {
  local source=$1 target=$2
  if [[ -f $source && ! -e $target ]]; then
    cp -- "$source" "$target"
    printf 'Created missing env file: %s\n' "$target"
  fi
}

prepare_env() {
  copy_if_missing .env.example .env
  local name
  for name in database rabbitmq storage security backend frontend observability mail; do
    copy_if_missing "config/env-files/$name.env.example" "config/env-files/$name.env"
  done
}

start_stack() {
  if ((skip_docker_build)); then
    TRACING_SAMPLING_PROBABILITY=1.0 \
      docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml up -d
  else
    TRACING_SAMPLING_PROBABILITY=1.0 \
      docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml up -d --build
  fi
  stack_started=1
}

wait_http() {
  local name=$1 url=$2 timeout=${3:-120} deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    if curl --fail --silent --show-error --max-time 5 --output /dev/null "$url"; then
      printf '%s is reachable at %s\n' "$name" "$url"
      return 0
    fi
    sleep 2
  done
  printf '%s did not become reachable at %s within %ss\n' "$name" "$url" "$timeout" >&2
  return 1
}

wait_docker_service_healthy() {
  local service=$1 timeout=${2:-120} deadline=$((SECONDS + timeout))
  local container_id="" status="container not found"
  while ((SECONDS < deadline)); do
    container_id=$(docker compose ps -q "$service" 2>/dev/null | head -n 1)
    if [[ -n $container_id ]]; then
      status=$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)
      if [[ $status == healthy ]]; then
        printf '%s container is healthy\n' "$service"
        return 0
      fi
    fi
    sleep 2
  done
  printf '%s did not become healthy within %ss. Last status: %s\n' "$service" "$timeout" "$status" >&2
  return 1
}

wait_services() {
  ((needs_backend_http == 0)) || wait_http Backend http://127.0.0.1:8080/actuator/health || return
  ((needs_frontend_http == 0)) || wait_http Frontend http://127.0.0.1:3000 || return
  ((needs_mailpit == 0)) || wait_http Mailpit http://127.0.0.1:8025/api/v1/messages || return
  ((needs_onlyoffice == 0)) || wait_http OnlyOffice http://127.0.0.1:8082/healthcheck || return
}

run_pytest() {
  local marker=$1 report=$2
  shift 2
  cd "$repo_root/backend-test" || return 2
  if [[ -x .venv/bin/pytest ]]; then
    .venv/bin/pytest -v -m "$marker" "--junitxml=$report" "$@"
  elif command -v uv >/dev/null; then
    UV_CACHE_DIR=../.uv-cache uv run pytest -v -m "$marker" "--junitxml=$report" "$@"
  else
    python3 -m pytest -v -m "$marker" "--junitxml=$report" "$@"
  fi
}

backend_tests() { (cd backend && mvn test -Dsurefire.reportNameSuffix=backend); }
integration_tests() { (cd backend && mvn verify -Pintegration -DskipUnitTests -pl betterclouddrive-dal,betterclouddrive-storage -am); }
blackbox_tests() { run_pytest 'not webdav and not office and not observability and not resilience and not security' ../test-results/blackbox/junit.xml; }
security_tests() { run_pytest security ../test-results/security/junit.xml; }
web_unit_tests() { (cd frontend && pnpm run test:coverage); }
web_e2e_tests() {
  (cd frontend && E2E_USE_DOCKER=1 E2E_BASE_URL=http://127.0.0.1:3000 \
    E2E_BACKEND_URL=http://127.0.0.1:8080 E2E_ONLYOFFICE=0 pnpm exec playwright test --project=core)
}
office_tests() {
  (cd frontend && E2E_USE_DOCKER=1 E2E_BASE_URL=http://127.0.0.1:3000 \
    E2E_BACKEND_URL=http://127.0.0.1:8080 E2E_ONLYOFFICE=1 pnpm exec playwright test --project=office)
}
tauri_build() { (cd frontend && pnpm run tauri:e2e:build); }
tauri_unit_tests() { (cd frontend/src-tauri && cargo test --locked); }
tauri_tests() { (cd frontend && pnpm run test:e2e:tauri); }
webdav_tests() { run_pytest webdav ../test-results/webdav/junit.xml; }
observability_tests() { run_pytest observability ../test-results/observability/junit.xml; }
reload_grafana_provisioning() {
  docker compose restart frontend grafana &&
    wait_docker_service_healthy frontend &&
    wait_docker_service_healthy grafana
}
prometheus_rule_tests() {
  docker compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml &&
    docker compose exec -T prometheus promtool check rules /etc/prometheus/rules/better-cloud-drive.yml &&
    docker compose exec -T prometheus promtool test rules /etc/prometheus/tests/better-cloud-drive.test.yml
}
resilience_tests() { run_pytest resilience ../test-results/resilience/junit.xml; }
android_unit_tests() { (cd android && ./gradlew testDebugUnitTest); }
android_coverage() { (cd android && ./gradlew jacocoDebugUnitTestCoverageVerification); }
android_e2e_tests() {
  if [[ $android_serial == *:* ]]; then adb connect "$android_serial" || return; fi
  (cd android && ANDROID_SERIAL="$android_serial" ./gradlew createDebugAndroidTestCoverageReport)
}

cleanup_e2e() {
  local env_file=config/env-files/database.env
  [[ -f $env_file ]] || env_file=config/env-files/database.env.example
  local pg_user pg_database
  pg_user=$(sed -n 's/^POSTGRES_USER=//p' "$env_file" | tail -1 | tr -d '\r"' | sed "s/^'//;s/'$//")
  pg_database=$(sed -n 's/^POSTGRES_DB=//p' "$env_file" | tail -1 | tr -d '\r"' | sed "s/^'//;s/'$//")
  pg_user=${pg_user:-bcd_user}
  pg_database=${pg_database:-better_cloud_drive}
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$pg_user" -d "$pg_database" < scripts/e2e-cleanup.sql
}

print_summary() {
  printf '\nValidation summary\n'
  printf '%-40s %-6s %8s  %s\n' Name Status Seconds Message
  local i
  for ((i=0; i<${#result_names[@]}; i++)); do
    printf '%-40s %-6s %8s  %s\n' \
      "${result_names[i]}" "${result_statuses[i]}" "${result_seconds[i]}" "${result_messages[i]}"
  done
}

finish() {
  local original_rc=$?
  trap - EXIT INT TERM
  if ((should_cleanup && stack_started)); then
    run_step "E2E data cleanup" cleanup_e2e || true
  fi
  print_summary
  if ((original_rc != 0 || had_failure)); then exit 1; fi
  exit 0
}
trap finish EXIT INT TERM

printf 'Selected modules: %s\n' "$(IFS=', '; echo "${!selected[*]}")"
printf 'Test run ID: %s\n' "$run_id"

run_step "Prepare env files" prepare_env || exit $?
((needs_docker == 0)) || run_step "Start Docker stack" start_stack || exit $?
((needs_docker == 0)) || run_step "Wait for services" wait_services || exit $?
((run_backend == 0)) || run_step "Backend unit tests" backend_tests || exit $?
((run_integration == 0)) || run_step "Backend Testcontainers integration tests" integration_tests || exit $?
((run_blackbox == 0)) || run_step "Backend black-box tests" blackbox_tests || exit $?
((run_security == 0)) || run_step "Security acceptance tests" security_tests || exit $?
((run_web == 0)) || run_step "Frontend unit tests and coverage" web_unit_tests || exit $?
((run_web == 0)) || run_step "Web E2E tests" web_e2e_tests || exit $?
((run_office == 0)) || run_step "OnlyOffice E2E tests" office_tests || exit $?
((run_tauri == 0)) || run_step "Tauri Rust unit tests" tauri_unit_tests || exit $?
((run_tauri == 0)) || run_step "Tauri E2E build" tauri_build || exit $?
((run_tauri == 0)) || run_step "Tauri E2E tests" tauri_tests || exit $?
((run_webdav == 0)) || run_step "WebDAV protocol and rclone acceptance" webdav_tests || exit $?
((run_observability == 0)) || run_step "Reload Grafana provisioning and gateway" reload_grafana_provisioning || exit $?
((run_observability == 0)) || run_step "Prometheus rule tests" prometheus_rule_tests || exit $?
((run_observability == 0)) || run_step "Observability acceptance" observability_tests || exit $?
((run_resilience == 0)) || run_step "Resilience acceptance" resilience_tests || exit $?
((run_android_unit == 0)) || run_step "Android unit tests" android_unit_tests || exit $?
((run_android_unit == 0)) || run_step "Android unit coverage and 80% gate" android_coverage || exit $?
((run_android == 0)) || run_step "Android E2E tests" android_e2e_tests || exit $?
