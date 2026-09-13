#!/usr/bin/env bash
#
# One-command burst script that reproduces the three graded invariants against a
# running wallet service.
#
#   Usage:  ./scripts/burst.sh [BASE_URL]
#           BASE_URL defaults to http://localhost:8080
#
#   Requires: bash, curl, jq.
#
# Exit code is 0 only if all three tests pass.

set -uo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
WALLET_CREATE_CONCURRENCY=50
IDEMPOTENCY_STORM=30
CONTENTION_WALLETS=5
CONTENTION_TRANSFERS=200
SEED_PAISE=100000

command -v jq >/dev/null 2>&1 || { echo "FATAL: jq is required"; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0
green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
ok()    { green "  PASS: $1"; pass=$((pass+1)); }
bad()   { red   "  FAIL: $1"; fail=$((fail+1)); }

# --- tiny API helpers -------------------------------------------------------
# create_wallet <token>  -> echoes wallet id
create_wallet() {
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $1" | jq -r '.id'
}
# deposit <token> <wallet_id> <amount> <key>
deposit() {
  curl -s -X POST "$BASE_URL/wallets/$2/deposit" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"amount_paise\":$3,\"idempotency_key\":\"$4\"}" >/dev/null
}
# balance <wallet_id> -> echoes balance_paise
balance() {
  curl -s "$BASE_URL/wallets/$1" | jq -r '.balance_paise'
}

echo "Target: $BASE_URL"
curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1 \
  && echo "Health: up" || { red "Health: DOWN — is the service running?"; exit 2; }
echo

# ===========================================================================
echo "[1/3] Concurrent get-or-create: $WALLET_CREATE_CONCURRENCY simultaneous POST /wallets for one fresh user"
USER="burst-$(date +%s)-$RANDOM"
seq "$WALLET_CREATE_CONCURRENCY" | xargs -P "$WALLET_CREATE_CONCURRENCY" -I IDX \
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER" -o "$TMP/w.IDX.json"
DISTINCT_WALLETS=$(cat "$TMP"/w.*.json | jq -r '.id' | sort -u | wc -l | tr -d ' ')
if [ "$DISTINCT_WALLETS" -eq 1 ]; then
  ok "exactly one wallet created ($DISTINCT_WALLETS distinct id)"
else
  bad "expected 1 wallet, got $DISTINCT_WALLETS distinct ids"
fi
echo

# ===========================================================================
echo "[2/3] Idempotent retry storm: same transfer key fired $IDEMPOTENCY_STORM times concurrently"
A=$(create_wallet "storm-a-$RANDOM")
B=$(create_wallet "storm-b-$RANDOM")
deposit "storm-a" "$A" "$SEED_PAISE" "seed-a-$RANDOM"
XFER=5000
KEY="idem-$(date +%s)-$RANDOM"
BODY="{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":$XFER,\"idempotency_key\":\"$KEY\"}"
seq "$IDEMPOTENCY_STORM" | xargs -P "$IDEMPOTENCY_STORM" -I IDX \
  curl -s -X POST "$BASE_URL/transfers" -H "Authorization: Bearer storm-a" \
  -H 'Content-Type: application/json' -d "$BODY" -o "$TMP/t.IDX.json"

DISTINCT_TX=$(cat "$TMP"/t.*.json | jq -r '.id' | sort -u | wc -l | tr -d ' ')
DISTINCT_RESP=$(cat "$TMP"/t.*.json | jq -cS '{id,status,amount_paise}' | sort -u | wc -l | tr -d ' ')
BAL_A=$(balance "$A"); BAL_B=$(balance "$B")
[ "$DISTINCT_TX" -eq 1 ]   && ok "one transfer id across all $IDEMPOTENCY_STORM responses" || bad "expected 1 transfer id, got $DISTINCT_TX"
[ "$DISTINCT_RESP" -eq 1 ] && ok "all responses identical" || bad "responses differ ($DISTINCT_RESP variants)"
[ "$BAL_A" -eq $((SEED_PAISE - XFER)) ] && ok "source debited exactly once (A=$BAL_A)" || bad "A debited wrong: expected $((SEED_PAISE - XFER)), got $BAL_A"
[ "$BAL_B" -eq "$XFER" ] && ok "destination credited exactly once (B=$BAL_B)" || bad "B credited wrong: expected $XFER, got $BAL_B"
echo

# ===========================================================================
echo "[3/3] Conservation under contention: $CONTENTION_TRANSFERS concurrent transfers among $CONTENTION_WALLETS wallets (incl. A->B and B->A)"
WALLETS=()
for i in $(seq "$CONTENTION_WALLETS"); do
  W=$(create_wallet "cont-$i-$RANDOM")
  deposit "cont-$i" "$W" "$SEED_PAISE" "seed-$i-$RANDOM"
  WALLETS+=("$W")
done
TOTAL_BEFORE=0
for W in "${WALLETS[@]}"; do TOTAL_BEFORE=$((TOTAL_BEFORE + $(balance "$W"))); done

# Build a job list: each line is "from to amount". Random distinct pairs; a mix
# of amounts, some large enough to be declined for insufficient funds.
: > "$TMP/jobs.txt"
for j in $(seq "$CONTENTION_TRANSFERS"); do
  fi=$((RANDOM % CONTENTION_WALLETS))
  ti=$((RANDOM % CONTENTION_WALLETS))
  while [ "$ti" -eq "$fi" ]; do ti=$((RANDOM % CONTENTION_WALLETS)); done
  amt=$(( (RANDOM % 3 + 1) * 20000 ))   # 20000 / 40000 / 60000 paise
  echo "${WALLETS[$fi]} ${WALLETS[$ti]} $amt $j" >> "$TMP/jobs.txt"
done

xargs -P 32 -L1 bash -c '
  from="$1"; to="$2"; amt="$3"; n="$4"
  curl -s -X POST "'"$BASE_URL"'/transfers" -H "Authorization: Bearer cont" \
    -H "Content-Type: application/json" \
    -d "{\"from\":\"$from\",\"to\":\"$to\",\"amount_paise\":$amt,\"idempotency_key\":\"cont-$n-$RANDOM\"}" >/dev/null
' _ < "$TMP/jobs.txt"

TOTAL_AFTER=0; ANY_NEGATIVE=0
for W in "${WALLETS[@]}"; do
  b=$(balance "$W")
  TOTAL_AFTER=$((TOTAL_AFTER + b))
  [ "$b" -lt 0 ] && ANY_NEGATIVE=1
done
[ "$TOTAL_AFTER" -eq "$TOTAL_BEFORE" ] && ok "total conserved (before=$TOTAL_BEFORE after=$TOTAL_AFTER)" || bad "total changed: before=$TOTAL_BEFORE after=$TOTAL_AFTER"
[ "$ANY_NEGATIVE" -eq 0 ] && ok "no wallet went negative" || bad "a wallet went negative"
echo

# ===========================================================================
echo "==================================================="
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ] && { green "ALL INVARIANTS HELD"; exit 0; } || { red "INVARIANT VIOLATION"; exit 1; }
