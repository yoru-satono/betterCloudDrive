#!/usr/bin/env bash

set -uo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/verify-all.sh [options]

Requires Bash 3.2 or newer.

Options:
  -m, --module NAME          Module to run; repeatable (default: all)
                            all, backend, integration, blackbox, security, web, tauri,
                            mobile-unit, mobile-android, office, webdav, observability, resilience
      --skip-docker-build   Start Compose without rebuilding images
      --skip-tauri          Skip Tauri (not allowed with --module all)
      --skip-android        Skip KMP Android device tests (not allowed with --module all)
      --skip-onlyoffice     Skip OnlyOffice (not allowed with --module all)
      --skip-e2e-cleanup    Do not remove this run's E2E data
      --android-serial ID   adb device serial for KMP tests (default: emulator-5556)
      --keep-going          Continue after a failed module
  -h, --help                Show this help
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

readonly -a valid_modules=(
  all backend integration blackbox security web tauri
  mobile-unit mobile-android office webdav observability resilience
)

contains() {
  local expected=$1
  shift
  local value
  for value in "$@"; do
    [[ $value == "$expected" ]] && return 0
  done
  return 1
}

is_valid_module() {
  contains "$1" "${valid_modules[@]}"
}

has_module() {
  contains "$1" "${selected_modules[@]}"
}

selected_modules=()
skip_docker_build=0
skip_tauri=0
skip_android=0
skip_onlyoffice=0
skip_e2e_cleanup=0
android_serial=emulator-5556
keep_going=0

while (( $# > 0 )); do
  case "$1" in
    -m|--module)
      (( $# >= 2 )) || die "$1 requires a module name"
      module_name=$(printf '%s' "$2" | LC_ALL=C tr '[:upper:]' '[:lower:]')
      [[ -n $module_name ]] || die "module name must not be empty"
      is_valid_module "$module_name" || die "unknown module: $module_name"
      has_module "$module_name" || selected_modules+=("$module_name")
      shift 2
      ;;
    --skip-docker-build)
      skip_docker_build=1
      shift
      ;;
    --skip-tauri)
      skip_tauri=1
      shift
      ;;
    --skip-android)
      skip_android=1
      shift
      ;;
    --skip-onlyoffice)
      skip_onlyoffice=1
      shift
      ;;
    --skip-e2e-cleanup)
      skip_e2e_cleanup=1
      shift
      ;;
    --android-serial)
      (( $# >= 2 )) || die "$1 requires a serial"
      android_serial=$2
      shift 2
      ;;
    --keep-going)
      keep_going=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

(( ${#selected_modules[@]} > 0 )) || selected_modules=(all)

if has_module all && (( skip_tauri || skip_android || skip_onlyoffice )); then
  die "module 'all' is a complete acceptance run; skip flags for Tauri, KMP Android, and OnlyOffice are not allowed"
fi

module_enabled() {
  local module=$1
  has_module all || has_module "$module" || return 1
  case "$module" in
    tauri) (( ! skip_tauri )) ;;
    mobile-android) (( ! skip_android )) ;;
    office) (( ! skip_onlyoffice )) ;;
    *) return 0 ;;
  esac
}

needs_backend_http=0
if module_enabled blackbox || module_enabled security || module_enabled web ||
   module_enabled tauri || module_enabled office || module_enabled webdav ||
   module_enabled observability || module_enabled resilience; then
  needs_backend_http=1
fi

needs_frontend_http=0
if module_enabled web || module_enabled tauri || module_enabled office; then
  needs_frontend_http=1
fi

needs_mailpit=0
if module_enabled blackbox || module_enabled web || module_enabled tauri || module_enabled office; then
  needs_mailpit=1
fi

needs_onlyoffice=0
module_enabled office && needs_onlyoffice=1
needs_docker=0
if (( needs_backend_http || needs_onlyoffice )); then needs_docker=1; fi
should_cleanup=0
if (( ! skip_e2e_cleanup && needs_docker )); then should_cleanup=1; fi

script_dir=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd) || exit 2
repo_root=$(cd -P "$script_dir/.." && pwd) || exit 2
readonly repo_root
cd "$repo_root" || exit 2

run_id=${BCD_TEST_RUN_ID:-bcd_$(date +%Y%m%d_%H%M%S)_$$}
export BCD_TEST_RUN_ID=$run_id
result_root=$repo_root/test-results
mkdir -p \
  "$result_root/backend" \
  "$result_root/integration" \
  "$result_root/blackbox" \
  "$result_root/security" \
  "$result_root/web" \
  "$result_root/tauri" \
  "$result_root/office" \
  "$result_root/webdav" \
  "$result_root/observability" \
  "$result_root/resilience" \
  "$result_root/coverage" || die "failed to create test result directories under $result_root"

if (( needs_docker )); then
  command -v docker >/dev/null 2>&1 || die "docker was not found on PATH"
  command -v curl >/dev/null 2>&1 || die "curl is required by modules that start the Docker stack"
fi
if module_enabled webdav; then
  command -v rclone >/dev/null 2>&1 || die "rclone is required by the webdav module"
fi
if module_enabled mobile-android; then
  command -v adb >/dev/null 2>&1 || die "adb is required by the KMP Android device module"
fi

summary_rows=()
had_failure=0
stack_started=0
rate_limits_overridden=0

run_step() {
  local step_name=$1
  shift
  local step_started step_ended step_seconds step_rc step_status step_message step_summary
  printf '\n==> %s\n' "$step_name"
  step_started=$(date +%s)
  "$@"
  step_rc=$?
  step_ended=$(date +%s)
  step_seconds=$((step_ended - step_started))
  if (( step_rc == 0 )); then
    step_status=PASS
    step_message=
  else
    step_status=FAIL
    step_message="exit code $step_rc"
    had_failure=1
  fi
  step_summary=$(printf '%-40s %-6s %8s  %s' "$step_name" "$step_status" "$step_seconds" "$step_message")
  summary_rows+=("$step_summary")
  printf '<== %s %s (%ss)\n' "$step_status" "$step_name" "$step_seconds"
  if (( step_rc != 0 && ! keep_going )); then
    return "$step_rc"
  fi
  return 0
}

copy_if_missing() {
  local copy_source=$1
  local copy_target=$2
  if [[ -f $copy_source && ! -e $copy_target ]]; then
    cp "$copy_source" "$copy_target" || return $?
    printf 'Created missing env file: %s\n' "$copy_target"
  fi
}

prepare_env() {
  local env_name
  copy_if_missing .env.example .env || return $?
  for env_name in database rabbitmq storage security backend frontend observability mail; do
    copy_if_missing "config/env-files/$env_name.env.example" "config/env-files/$env_name.env" || return $?
  done
}

start_stack() {
  if (( skip_docker_build )); then
    TRACING_SAMPLING_PROBABILITY=1.0 \
      docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml up -d || return $?
  else
    TRACING_SAMPLING_PROBABILITY=1.0 \
      docker compose -f docker-compose.yml -f docker-compose.diagnostics.yml up -d --build || return $?
  fi
  stack_started=1
}

wait_http() {
  local http_name=$1
  local http_url=$2
  local http_timeout=${3:-120}
  local http_deadline=$(( $(date +%s) + http_timeout ))
  while (( $(date +%s) < http_deadline )); do
    if curl --fail --silent --show-error --max-time 5 --output /dev/null "$http_url"; then
      printf '%s is reachable at %s\n' "$http_name" "$http_url"
      return 0
    fi
    sleep 2
  done
  printf '%s did not become reachable at %s within %ss\n' "$http_name" "$http_url" "$http_timeout" >&2
  return 1
}

wait_docker_service_healthy() {
  local docker_service=$1
  local docker_timeout=${2:-120}
  local docker_deadline=$(( $(date +%s) + docker_timeout ))
  local docker_container_id=
  local docker_status="container not found"
  while (( $(date +%s) < docker_deadline )); do
    docker_container_id=$(docker compose ps -q "$docker_service" 2>/dev/null | head -n 1)
    if [[ -n $docker_container_id ]]; then
      docker_status=$(docker inspect --format '{{.State.Health.Status}}' "$docker_container_id" 2>/dev/null || :)
      if [[ $docker_status == healthy ]]; then
        printf '%s container is healthy\n' "$docker_service"
        return 0
      fi
    fi
    sleep 2
  done
  printf '%s did not become healthy within %ss. Last status: %s\n' "$docker_service" "$docker_timeout" "$docker_status" >&2
  return 1
}

wait_services() {
  if (( needs_backend_http )); then
    wait_http "Backend management" http://127.0.0.1:8081/actuator/health || return $?
  fi
  if (( needs_frontend_http )); then
    wait_http Frontend http://127.0.0.1:3000 || return $?
  fi
  if (( needs_mailpit )); then
    wait_http Mailpit http://127.0.0.1:8025/api/v1/messages || return $?
  fi
  if (( needs_onlyoffice )); then
    wait_http OnlyOffice http://127.0.0.1:8082/healthcheck || return $?
  fi
}

run_pytest() {
  local pytest_marker=$1
  local pytest_report=$2
  shift 2
  (
    cd "$repo_root/backend-test" || exit 2
    if [[ -x .venv/bin/pytest ]]; then
      .venv/bin/pytest -v -m "$pytest_marker" "--junitxml=$pytest_report" "$@"
    elif command -v uv >/dev/null 2>&1; then
      UV_CACHE_DIR=../.uv-cache uv run pytest -v -m "$pytest_marker" "--junitxml=$pytest_report" "$@"
    else
      python3 -m pytest -v -m "$pytest_marker" "--junitxml=$pytest_report" "$@"
    fi
  )
}

backend_tests() {
  (cd backend && mvn test -Dsurefire.reportNameSuffix=backend)
}

integration_tests() {
  (cd backend && mvn verify -Pintegration -DskipUnitTests \
    -pl betterclouddrive-dal,betterclouddrive-storage,betterclouddrive-service,betterclouddrive-web -am)
}

blackbox_tests() {
  with_functional_test_rate_limits run_pytest \
    'not webdav and not office and not observability and not resilience and not security' \
    ../test-results/blackbox/junit.xml
}

security_tests() {
  run_pytest security ../test-results/security/junit.xml
}

web_unit_tests() {
  (cd frontend && pnpm run test:coverage)
}

web_e2e_tests() {
  with_functional_test_rate_limits run_web_e2e
}

run_web_e2e() {
  (cd frontend && E2E_ONLYOFFICE=0 \
    node scripts/run-playwright-docker.mjs --project=core)
}

office_tests() {
  (cd frontend && E2E_ONLYOFFICE=1 \
    node scripts/run-playwright-docker.mjs --project=office)
}

tauri_build() {
  (cd frontend && pnpm run tauri:e2e:build)
}

tauri_unit_tests() {
  (cd frontend/src-tauri && cargo test --locked)
}

set_functional_test_rate_limits() {
  local rate_limit_rc
  DRIVE_RATE_LIMIT_DEFAULT_PERMITS=10000 \
    DRIVE_RATE_LIMIT_DEFAULT_PERIOD_SECONDS=1 \
    DRIVE_RATE_LIMIT_AUTH_PERMITS=10000 \
    DRIVE_RATE_LIMIT_AUTH_PERIOD_SECONDS=1 \
    DRIVE_RATE_LIMIT_UPLOAD_PERMITS=10000 \
    DRIVE_RATE_LIMIT_UPLOAD_PERIOD_SECONDS=1 \
    DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERMITS=10000 \
    DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERIOD_SECONDS=1 \
    docker compose up -d --no-deps --force-recreate backend || return $?
  rate_limits_overridden=1
  wait_docker_service_healthy backend || {
    rate_limit_rc=$?
    restore_default_rate_limits || :
    return "$rate_limit_rc"
  }
  docker compose restart frontend || {
    rate_limit_rc=$?
    restore_default_rate_limits || :
    return "$rate_limit_rc"
  }
  wait_docker_service_healthy frontend || {
    rate_limit_rc=$?
    restore_default_rate_limits || :
    return "$rate_limit_rc"
  }
}

restore_default_rate_limits() {
  local restore_rc
  docker compose up -d --no-deps --force-recreate backend || return $?
  wait_docker_service_healthy backend &&
    docker compose restart frontend &&
    wait_docker_service_healthy frontend
  restore_rc=$?
  if (( restore_rc == 0 )); then
    rate_limits_overridden=0
  fi
  return "$restore_rc"
}

with_functional_test_rate_limits() {
  local command_rc=0
  local restore_rc=0
  set_functional_test_rate_limits || return $?
  "$@" || command_rc=$?
  restore_default_rate_limits || restore_rc=$?
  (( command_rc == 0 )) || return "$command_rc"
  return "$restore_rc"
}

tauri_tests() {
  with_functional_test_rate_limits run_tauri_e2e
}

run_tauri_e2e() {
  (cd frontend && pnpm run test:e2e:tauri)
}

webdav_tests() {
  run_pytest webdav ../test-results/webdav/junit.xml
}

observability_tests() {
  run_pytest observability ../test-results/observability/junit.xml
}

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

resilience_tests() {
  run_pytest resilience ../test-results/resilience/junit.xml
}

mobile_unit_tests() {
  (cd mobile && bash ./gradlew \
    :shared:testAndroidHostTest \
    :androidApp:assembleDebug \
    :androidApp:assembleRelease \
    -PenableIos=true \
    :shared:compileIosMainKotlinMetadata)
}

mobile_android_tests() {
  case "$android_serial" in
    *:*) adb connect "$android_serial" || return $? ;;
  esac
  (cd mobile && ANDROID_SERIAL=$android_serial bash ./gradlew :androidApp:connectedDebugAndroidTest)
}

cleanup_e2e() {
  local cleanup_env_file=config/env-files/database.env
  local cleanup_pg_user cleanup_pg_database
  [[ -f $cleanup_env_file ]] || cleanup_env_file=config/env-files/database.env.example
  cleanup_pg_user=$(sed -n 's/^POSTGRES_USER=//p' "$cleanup_env_file" | tail -1 | tr -d '\r"' | sed "s/^'//;s/'$//") || return $?
  cleanup_pg_database=$(sed -n 's/^POSTGRES_DB=//p' "$cleanup_env_file" | tail -1 | tr -d '\r"' | sed "s/^'//;s/'$//") || return $?
  cleanup_pg_user=${cleanup_pg_user:-bcd_user}
  cleanup_pg_database=${cleanup_pg_database:-better_cloud_drive}
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U "$cleanup_pg_user" -d "$cleanup_pg_database" < scripts/e2e-cleanup.sql
}

print_summary() {
  printf '\nValidation summary\n'
  printf '%-40s %-6s %8s  %s\n' Name Status Seconds Message
  if (( ${#summary_rows[@]} > 0 )); then
    printf '%s\n' "${summary_rows[@]}"
  fi
}

finish() {
  local finish_original_rc=$1
  trap - EXIT HUP INT TERM
  if (( rate_limits_overridden )); then
    run_step "Restore backend default rate limits" restore_default_rate_limits || :
  fi
  if (( should_cleanup && stack_started )); then
    run_step "E2E data cleanup" cleanup_e2e || :
  fi
  print_summary
  if (( finish_original_rc != 0 || had_failure )); then exit 1; fi
  exit 0
}

trap 'finish $?' EXIT
trap 'finish 129' HUP
trap 'finish 130' INT
trap 'finish 143' TERM

selected_display=$(IFS=,; printf '%s' "${selected_modules[*]}")
selected_display=${selected_display//,/, }
printf 'Selected modules: %s\n' "$selected_display"
printf 'Test run ID: %s\n' "$run_id"

run_step "Prepare env files" prepare_env || exit $?
if (( needs_docker )); then
  run_step "Start Docker stack" start_stack || exit $?
  run_step "Wait for services" wait_services || exit $?
fi
if module_enabled backend; then
  run_step "Backend unit tests" backend_tests || exit $?
fi
if module_enabled integration; then
  run_step "Backend Testcontainers integration tests" integration_tests || exit $?
fi
if module_enabled blackbox; then
  run_step "Backend black-box tests" blackbox_tests || exit $?
fi
if module_enabled security; then
  run_step "Security acceptance tests" security_tests || exit $?
fi
if module_enabled web; then
  run_step "Frontend unit tests and coverage" web_unit_tests || exit $?
  run_step "Web E2E tests" web_e2e_tests || exit $?
fi
if module_enabled office; then
  run_step "OnlyOffice E2E tests" office_tests || exit $?
fi
if module_enabled tauri; then
  run_step "Tauri Rust unit tests" tauri_unit_tests || exit $?
  run_step "Tauri E2E build" tauri_build || exit $?
  run_step "Tauri E2E tests" tauri_tests || exit $?
fi
if module_enabled webdav; then
  run_step "WebDAV protocol and rclone acceptance" webdav_tests || exit $?
fi
if module_enabled observability; then
  run_step "Reload Grafana provisioning and gateway" reload_grafana_provisioning || exit $?
  run_step "Prometheus rule tests" prometheus_rule_tests || exit $?
  run_step "Observability acceptance" observability_tests || exit $?
fi
if module_enabled resilience; then
  run_step "Resilience acceptance" resilience_tests || exit $?
fi
if module_enabled mobile-unit; then
  run_step "KMP host tests, Android builds, and iOS metadata" mobile_unit_tests || exit $?
fi
if module_enabled mobile-android; then
  run_step "KMP mobile Android device tests" mobile_android_tests || exit $?
fi
