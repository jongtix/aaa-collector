#!/usr/bin/env bash
#
# SPEC-COLLECTOR-WS-SAFEMODE-EXIT-001 REQ-WSEXIT-006
# 레거시 TTL-없는(영구) WS 안전모드 키를 일회성으로 정리한다.
#
# v1.59.0(WS-RESILIENCE-001, #99) 이전에 생성된 "safe_mode:collector:ws:*" 키는
# TTL 없이 영구 저장되었다. 이후 exit() 호출 경로 부재(aaa-infra#114)와 겹치면
# 키가 영구 락으로 잔존할 수 있다. 이 스크립트는 TTL이 -1(영구)인 키만 골라
# 삭제(DEL, 기본) 또는 유한 TTL 소급 부여(EXPIRE)한다. TTL이 이미 설정된 정상
# 키는 절대 건드리지 않는다.
#
# 사용법:
#   REDIS_HOST=... REDIS_PORT=... REDIS_APPUSER_USERNAME=... REDIS_APPUSER_PASSWORD=... \
#     ./scripts/ops/cleanup-legacy-safemode-keys.sh [--dry-run] [--expire <seconds>]
#
#   --dry-run          실제 변경 없이 대상 키 목록만 출력한다.
#   --expire <seconds> DEL 대신 EXPIRE로 유한 TTL을 소급 부여한다(초 단위).
#
# 자격 증명은 환경변수로만 전달한다(ps 노출 방지). REDISCLI_AUTH를 사용해
# 비밀번호가 커맨드라인 인자로 노출되지 않도록 한다.
#
# 이 스크립트는 REQ-WSEXIT-006 구현 방식 (b)로 확정되었다(Run Phase 1 승인
# 게이트, 2026-07-24) — Java 코드·자동 테스트 대상이 아니며 운영 절차로
# 수동 실행한다. 검증 방법은 acceptance.md AC-6 참조.

set -euo pipefail

KEY_PATTERN="safe_mode:collector:ws:*"
DRY_RUN=0
EXPIRE_SECONDS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --expire)
      EXPIRE_SECONDS="$2"
      shift 2
      ;;
    *)
      echo "알 수 없는 인자: $1" >&2
      exit 1
      ;;
  esac
done

: "${REDIS_HOST:?REDIS_HOST 환경변수가 필요합니다}"
: "${REDIS_PORT:?REDIS_PORT 환경변수가 필요합니다}"
: "${REDIS_APPUSER_USERNAME:?REDIS_APPUSER_USERNAME 환경변수가 필요합니다}"
: "${REDIS_APPUSER_PASSWORD:?REDIS_APPUSER_PASSWORD 환경변수가 필요합니다}"

export REDISCLI_AUTH="${REDIS_APPUSER_PASSWORD}"

redis_cli() {
  redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" --user "${REDIS_APPUSER_USERNAME}" "$@"
}

echo "스캔 대상 패턴: ${KEY_PATTERN}"
[[ "${DRY_RUN}" -eq 1 ]] && echo "[dry-run] 실제 변경은 수행하지 않습니다."

cursor=0
processed=0
targeted=0

while :; do
  scan_result=$(redis_cli SCAN "${cursor}" MATCH "${KEY_PATTERN}" COUNT 100)
  cursor=$(echo "${scan_result}" | head -n1)
  keys=$(echo "${scan_result}" | tail -n +2)

  for key in ${keys}; do
    [[ -z "${key}" ]] && continue
    processed=$((processed + 1))

    ttl=$(redis_cli TTL "${key}")
    if [[ "${ttl}" != "-1" ]]; then
      # TTL이 있는 정상 키는 건드리지 않는다.
      continue
    fi

    targeted=$((targeted + 1))
    if [[ "${DRY_RUN}" -eq 1 ]]; then
      echo "[dry-run] 대상: ${key} (TTL=-1)"
      continue
    fi

    if [[ -n "${EXPIRE_SECONDS}" ]]; then
      redis_cli EXPIRE "${key}" "${EXPIRE_SECONDS}" >/dev/null
      echo "EXPIRE 부여: ${key} -> ${EXPIRE_SECONDS}s"
    else
      redis_cli DEL "${key}" >/dev/null
      echo "삭제: ${key}"
    fi
  done

  [[ "${cursor}" == "0" ]] && break
done

echo "스캔 완료: 총 ${processed}개 키 확인, ${targeted}개 TTL-없는 키 정리 대상."
