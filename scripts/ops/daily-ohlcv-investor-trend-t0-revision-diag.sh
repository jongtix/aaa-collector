#!/usr/bin/env bash
#
# SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-031, -032, -033, -070, -071
#
# daily_ohlcv / investor_trend T+0 예비치(preliminary value) 소급 정정 진단 스크립트.
#
# 이 스크립트는 2단계 수동 SQL 정정 절차(REQ-T0R-031)의 1단계(진단)만 수행한다 — DB를
# 절대 수정하지 않는다(REQ-T0R-032):
#   (a) 대상 종목·날짜에 대해 국내 일봉(TR FHKST03010100)·투자자매매동향(TR FHPTJ04160001)을
#       KIS에 라이브 재조회한다.
#   (b) DB 저장값과 비교해 diff 리포트를 생성한다.
#   (c) 검토용 UPDATE문 초안(root 실행 대상)을 생성한다 — 이 스크립트가 직접 실행하지 않는다.
#
# 오퍼레이터가 산출된 diff 리포트를 검토한 뒤, (c)의 UPDATE문 초안을 별도로 MYSQL_PWD
# 환경변수 방식(프로젝트 관례)으로 root 권한 직접 실행한다(REQ-T0R-032, -033 — 애플리케이션
# 코드로 구현하지 않으며, 이 스크립트 자체도 UPDATE 경로를 갖지 않는다).
#
# 감사 추적(REQ-T0R-070/071): diff 리포트는 날짜가 포함된 파일 경로에 저장된다. 오퍼레이터가
# 실제 UPDATE를 실행한 뒤, 같은 파일의 "## 실행 결과" 섹션에 실행 타임스탬프·실행자·영향 행
# 수·before/after 값을 직접 기록한다(본 스크립트는 그 섹션의 표 틀만 생성해 둔다) — 콘솔
# 출력만으로는 REQ-T0R-071을 충족하지 못한다.
#
# 대상 재확인 절차(plan.md §M5): 이 스크립트는 대상 종목·날짜 목록을 하드코딩하지 않는다 —
# 매 실행 시 TARGETS_FILE(또는 SYMBOL/DATE)로 호출자가 "2026-06-29 ~ 오늘"이 아니라
# "2026-06-29 ~ REQ-T0R-001 배포 확정 시점"의 그 시점 실제 누적 대상을 다시 계산해 전달해야
# 한다(REQ-T0R-011). 이 스크립트 자체는 대상 산출 로직을 갖지 않는다 — 별도로 산출한 대상
# 목록을 인자로 받는다.
#
# 필요 환경변수:
#   KIS_BASE_URL              예) https://openapi.koreainvestment.com:9443
#   KIS_ACCT_ISA_APP_KEY      (또는 KIS_PROBE_APP_KEY 로 override)
#   KIS_ACCT_ISA_APP_SECRET   (또는 KIS_PROBE_APP_SECRET 로 override)
#   DB_HOST / DB_PORT / DB_NAME / DB_USER   DB 접속 정보 (SELECT 권한만 필요 — 이 스크립트는
#                                            읽기 전용이다)
#   MYSQL_PWD                  DB 접속 비밀번호 — mysql CLI가 자동 인식(-p 인자 미사용,
#                               ps/docker top 비노출, guard-credential-exposure.sh 안전 패턴)
#
# 대상 지정 (둘 중 하나, 필수):
#   TARGETS_FILE=path/to/targets.tsv   "종목코드<TAB>YYYYMMDD" 1행 1건 형식
#   또는 SYMBOL=005930 DATE=20260701   단일 대상
#
# 산출물 (감사 추적 아티팩트, REQ-T0R-070):
#   ${T0R_REPORT_DIR:-<repo-root>/.moai/reports/t0r-correction}/<RUN_DATE>-diff.md
#   T0R_REPORT_DIR로 절대 경로를 지정하면 aaa/.moai/reports/t0r-correction/ 등 프로젝트
#   공통 아티팩트 디렉터리로 직접 출력할 수 있다(worktree 실행 시 권장 — 기본값은
#   aaa-collector가 aaa/ 바로 하위에 있는 표준 배치를 가정한다).
#
# 사용법:
#   TARGETS_FILE=targets.tsv ./daily-ohlcv-investor-trend-t0-revision-diag.sh
#   SYMBOL=005930 DATE=20260701 ./daily-ohlcv-investor-trend-t0-revision-diag.sh
#
# 보안 (CLAUDE.md Security 준수):
#   - .env 파일을 읽지 않는다. export된 환경변수만 사용한다.
#   - KIS 시크릿은 stdin으로만 전달(kis-probe.sh 패턴 답습, ps 비노출).
#   - DB 비밀번호는 MYSQL_PWD 환경변수로만 전달(-p 인자 미사용).
#   - 응답의 access_token은 REDACT.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="${T0R_REPORT_DIR:-$SCRIPT_DIR/../../../.moai/reports/t0r-correction}"
mkdir -p "$REPORT_DIR"
chmod 700 "$REPORT_DIR" 2>/dev/null || true

RUN_DATE="$(date +%Y%m%d)"
REPORT_FILE="$REPORT_DIR/${RUN_DATE}-diff.md"

: "${KIS_BASE_URL:?KIS_BASE_URL 미설정 — export 후 재실행}"
APPKEY="${KIS_PROBE_APP_KEY:-${KIS_ACCT_ISA_APP_KEY:-}}"
APPSECRET="${KIS_PROBE_APP_SECRET:-${KIS_ACCT_ISA_APP_SECRET:-}}"
[[ -n "$APPKEY" && -n "$APPSECRET" ]] || { echo "ERROR: KIS_ACCT_ISA_APP_KEY/SECRET (또는 KIS_PROBE_*) 미설정" >&2; exit 1; }

: "${DB_HOST:?DB_HOST 미설정}"
: "${DB_PORT:?DB_PORT 미설정}"
: "${DB_NAME:?DB_NAME 미설정}"
: "${DB_USER:?DB_USER 미설정}"
: "${MYSQL_PWD:?MYSQL_PWD 미설정 (DB 비밀번호 — -p 인자 대신 이 환경변수를 사용할 것)}"

# --- DB 조회 헬퍼 — 읽기 전용(SELECT만), -p 인자 미사용(MYSQL_PWD가 mysql CLI에 의해 자동 인식됨) ---
mysql_query() { # $1=SQL. stdout: 탭 구분 결과(헤더 없음, -N -B)
  mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -B "$DB_NAME" -e "$1"
}

get_token() { # stdout: bearer token
  local resp tok
  resp="$(printf '{"grant_type":"client_credentials","appkey":"%s","appsecret":"%s"}' "$APPKEY" "$APPSECRET" \
    | curl -sS -X POST "$KIS_BASE_URL/oauth2/tokenP" -H 'content-type: application/json; charset=utf-8' --data @-)"
  tok="$(printf '%s' "$resp" | jq -r '.access_token // empty' 2>/dev/null || true)"
  [[ -n "$tok" ]] || { echo "토큰 발급 실패: $(printf '%s' "$resp" | head -c 300 | sed 's/"access_token":"[^"]*"/"access_token":"***REDACTED***"/')" >&2; exit 1; }
  printf '%s' "$tok"
}

fetch_daily_ohlcv() { # $1=symbol $2=date(YYYYMMDD). stdout: raw JSON (output2 단일 행 기대)
  local symbol="$1" date="$2"
  printf 'url = "%s/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=%s&FID_INPUT_DATE_1=%s&FID_INPUT_DATE_2=%s&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1"\nheader = "content-type: application/json; charset=utf-8"\nheader = "authorization: Bearer %s"\nheader = "appkey: %s"\nheader = "appsecret: %s"\nheader = "tr_id: FHKST03010100"\nheader = "custtype: P"\n' \
    "$KIS_BASE_URL" "$symbol" "$date" "$date" "$TOKEN" "$APPKEY" "$APPSECRET" \
    | curl -sS --config -
}

fetch_investor_trend() { # $1=symbol $2=date(YYYYMMDD). stdout: raw JSON (output2 단일 행 기대)
  local symbol="$1" date="$2"
  printf 'url = "%s/uapi/domestic-stock/v1/quotations/investor-trade-by-stock-daily?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=%s&FID_INPUT_DATE_1=%s&FID_ORG_ADJ_PRC=&FID_ETC_CLS_CODE=1"\nheader = "content-type: application/json; charset=utf-8"\nheader = "authorization: Bearer %s"\nheader = "appkey: %s"\nheader = "appsecret: %s"\nheader = "tr_id: FHPTJ04160001"\nheader = "custtype: P"\n' \
    "$KIS_BASE_URL" "$symbol" "$date" "$TOKEN" "$APPKEY" "$APPSECRET" \
    | curl -sS --config -
}

_diff_row() { # $1=필드명 $2=DB값 $3=라이브값. stdout: markdown 표 1행
  local name="$1" db="${2:-NULL}" live="${3:-N/A}" mark
  if [[ -z "$live" || "$live" == "N/A" ]]; then
    mark="라이브 값 없음"
  elif [[ "$db" == "$live" ]]; then
    mark="일치"
  else
    mark="**불일치**"
  fi
  printf '| %s | %s | %s | %s |\n' "$name" "${db:-NULL}" "${live:-N/A}" "$mark"
}

diag_one() { # $1=symbol $2=date(YYYYMMDD)
  local symbol="$1" date="$2"
  local iso_date="${date:0:4}-${date:4:2}-${date:6:2}"

  # DB 저장값 조회 (stock_id는 symbol로 JOIN)
  local db_ohlcv db_it
  db_ohlcv="$(mysql_query "
    SELECT o.volume, o.trading_value
    FROM daily_ohlcv o JOIN stocks s ON o.stock_id = s.id
    WHERE s.symbol = '${symbol}' AND o.trade_date = '${iso_date}'
  ")"
  db_it="$(mysql_query "
    SELECT it.foreign_net_qty, it.institution_net_qty, it.individual_net_qty,
           it.foreign_net_value, it.institution_net_value, it.individual_net_value,
           it.total_volume, it.total_trading_value
    FROM investor_trend it JOIN stocks s ON it.stock_id = s.id
    WHERE s.symbol = '${symbol}' AND it.trade_date = '${iso_date}'
  ")"

  # KIS 라이브 재조회
  local ohlcv_json it_json
  ohlcv_json="$(fetch_daily_ohlcv "$symbol" "$date")"
  it_json="$(fetch_investor_trend "$symbol" "$date")"

  local live_volume live_tr_pbmn
  live_volume="$(printf '%s' "$ohlcv_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .acml_vol // empty')"
  live_tr_pbmn="$(printf '%s' "$ohlcv_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .acml_tr_pbmn // empty')"

  local live_frgn live_orgn live_prsn live_frgn_amt live_orgn_amt live_prsn_amt live_tot_vol live_tot_amt
  live_frgn="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .frgn_ntby_qty // empty')"
  live_orgn="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .orgn_ntby_qty // empty')"
  live_prsn="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .prsn_ntby_qty // empty')"
  live_frgn_amt="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .frgn_ntby_tr_pbmn // empty')"
  live_orgn_amt="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .orgn_ntby_tr_pbmn // empty')"
  live_prsn_amt="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .prsn_ntby_tr_pbmn // empty')"
  live_tot_vol="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .acml_vol // empty')"
  live_tot_amt="$(printf '%s' "$it_json" | jq -r --arg d "$date" '.output2[]? | select(.stck_bsop_date==$d) | .acml_tr_pbmn // empty')"

  {
    echo "### ${symbol} ${iso_date}"
    echo ""
    echo "**daily_ohlcv**"
    echo ""
    echo "| 필드 | DB 저장값 | 라이브 재조회 | 판정 |"
    echo "|------|-----------|----------------|------|"
    _diff_row "volume" "$(printf '%s' "$db_ohlcv" | cut -f1)" "$live_volume"
    _diff_row "trading_value" "$(printf '%s' "$db_ohlcv" | cut -f2)" "$live_tr_pbmn"
    echo ""
    echo "**investor_trend**"
    echo ""
    echo "| 필드 | DB 저장값 | 라이브 재조회 | 판정 |"
    echo "|------|-----------|----------------|------|"
    _diff_row "foreign_net_qty" "$(printf '%s' "$db_it" | cut -f1)" "$live_frgn"
    _diff_row "institution_net_qty" "$(printf '%s' "$db_it" | cut -f2)" "$live_orgn"
    _diff_row "individual_net_qty" "$(printf '%s' "$db_it" | cut -f3)" "$live_prsn"
    _diff_row "foreign_net_value" "$(printf '%s' "$db_it" | cut -f4)" "$live_frgn_amt"
    _diff_row "institution_net_value" "$(printf '%s' "$db_it" | cut -f5)" "$live_orgn_amt"
    _diff_row "individual_net_value" "$(printf '%s' "$db_it" | cut -f6)" "$live_prsn_amt"
    _diff_row "total_volume" "$(printf '%s' "$db_it" | cut -f7)" "$live_tot_vol"
    _diff_row "total_trading_value" "$(printf '%s' "$db_it" | cut -f8)" "$live_tot_amt"
    echo ""

    # 검토용 UPDATE문 초안 — 이 스크립트는 실행하지 않는다(REQ-T0R-032). 불일치가 있을 때만 생성.
    if [[ -n "$live_volume" && "$(printf '%s' "$db_ohlcv" | cut -f1)" != "$live_volume" ]]; then
      echo '```sql'
      echo "-- 검토용 초안 — 자동 실행되지 않음. 오퍼레이터가 검토 후 MYSQL_PWD 방식으로 root 권한 직접 실행할 것."
      echo "UPDATE daily_ohlcv o JOIN stocks s ON o.stock_id = s.id"
      echo "SET o.volume = ${live_volume}, o.trading_value = ${live_tr_pbmn:-0}"
      echo "WHERE s.symbol = '${symbol}' AND o.trade_date = '${iso_date}';"
      echo '```'
      echo ""
    fi
    if [[ -n "$live_frgn" ]]; then
      echo '```sql'
      echo "-- 검토용 초안 — 자동 실행되지 않음. 오퍼레이터가 검토 후 MYSQL_PWD 방식으로 root 권한 직접 실행할 것."
      echo "UPDATE investor_trend it JOIN stocks s ON it.stock_id = s.id"
      echo "SET it.foreign_net_qty = ${live_frgn}, it.institution_net_qty = ${live_orgn:-0}, it.individual_net_qty = ${live_prsn:-0},"
      echo "    it.foreign_net_value = ${live_frgn_amt:-0}, it.institution_net_value = ${live_orgn_amt:-0}, it.individual_net_value = ${live_prsn_amt:-0},"
      echo "    it.total_volume = ${live_tot_vol:-0}, it.total_trading_value = ${live_tot_amt:-0}"
      echo "WHERE s.symbol = '${symbol}' AND it.trade_date = '${iso_date}';"
      echo '```'
      echo ""
    fi
  } >> "$REPORT_FILE"
}

main() {
  {
    echo "# T0R 소급 정정 진단 리포트 — ${RUN_DATE}"
    echo ""
    echo "SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-031(진단 절차) / REQ-T0R-070,-071(감사 추적)"
    echo ""
    echo "이 리포트는 \`daily_ohlcv\`/\`investor_trend\` 두 테이블의 T+0 예비치 소급 정정 대상"
    echo "후보에 대해 DB 저장값과 KIS 라이브 재조회값을 비교한다. 이 스크립트 자체는 UPDATE를"
    echo "실행하지 않는다(REQ-T0R-032) — 아래 diff와 검토용 UPDATE문 초안을 검토한 뒤 오퍼레이터가"
    echo "별도로 MYSQL_PWD 방식(프로세스 인자 비노출)으로 root 권한 직접 실행한다."
    echo ""
  } > "$REPORT_FILE"

  TOKEN="$(get_token)"

  if [[ -n "${TARGETS_FILE:-}" ]]; then
    [[ -f "$TARGETS_FILE" ]] || { echo "ERROR: TARGETS_FILE '$TARGETS_FILE' 이 존재하지 않음" >&2; exit 1; }
    while IFS=$'\t' read -r symbol date; do
      [[ -n "$symbol" && -n "$date" ]] || continue
      diag_one "$symbol" "$date"
    done < "$TARGETS_FILE"
  elif [[ -n "${SYMBOL:-}" && -n "${DATE:-}" ]]; then
    diag_one "$SYMBOL" "$DATE"
  else
    echo "ERROR: TARGETS_FILE 또는 SYMBOL+DATE 중 하나를 설정할 것" >&2
    exit 1
  fi

  {
    echo "## 실행 결과 (오퍼레이터가 실제 UPDATE 실행 후 직접 기록 — REQ-T0R-070, -071)"
    echo ""
    echo "콘솔 출력만으로는 감사 추적 요건을 충족하지 못한다(REQ-T0R-071) — 아래 표를 실제"
    echo "실행 후 채울 것."
    echo ""
    echo "| 실행 타임스탬프 | 실행자 | 대상 테이블 | 영향 행 수 | 비고(before → after) |"
    echo "|-----------------|--------|-------------|-----------|------------------------|"
    echo "| (미기록) | (미기록) | (미기록) | (미기록) | 오퍼레이터가 root 실행 후 이 행을 채울 것 |"
    echo ""
  } >> "$REPORT_FILE"

  echo "진단 완료 — 리포트: $REPORT_FILE"
}

main "$@"
