#!/usr/bin/env bash
#
# One-command burst script. Reproduces the three graded concurrency invariants
# plus the authorization check (a caller cannot move money out of a wallet they
# do not own) against a running wallet service.
#
#   Usage:  ./scripts/burst.sh [BASE_URL]
#           BASE_URL defaults to http://localhost:8080
#
#   Requires: bash, curl, jq.
#
# Exit code is 0 only if every check passes.

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
# The bearer token IS the user id; every call uses the token that OWNS the
# wallet it acts on, because the API scopes transfers/reads/deposits to the owner.
# create_wallet <token>  -> echoes wallet id
create_wallet() {
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $1" | jq -r '.id'
}
# deposit <owner_token> <wallet_id> <amount> <key>
deposit() {
  curl -s -X POST "$BASE_URL/wallets/$2/deposit" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"amount_paise\":$3,\"idempotency_key\":\"$4\"}" >/dev/null
}
# balance <owner_token> <wallet_id> -> echoes balance_paise
balance() {
  curl -s "$BASE_URL/wallets/$2" -H "Authorization: Bearer $1" | jq -r '.balance_paise'
}

echo "Target: $BASE_URL"
curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1 \
  && echo "Health: up" || { red "Health: DOWN — is the service running?"; exit 2; }
echo

# ===========================================================================
echo "[1/4] Concurrent get-or-create: $WALLET_CREATE_CONCURRENCY simultaneous POST /wallets for one fresh user"
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
echo "[2/4] Idempotent retry storm: same transfer key fired $IDEMPOTENCY_STORM times concurrently"
ATOK="storm-a-$RANDOM"; BTOK="storm-b-$RANDOM"
A=$(create_wallet "$ATOK")
B=$(create_wallet "$BTOK")
deposit "$ATOK" "$A" "$SEED_PAISE" "seed-a-$RANDOM"
XFER=5000
KEY="idem-$(date +%s)-$RANDOM"
BODY="{\"from\":\"$A\",\"to\":\"$B\",\"amount_paise\":$XFER,\"idempotency_key\":\"$KEY\"}"
seq "$IDEMPOTENCY_STORM" | xargs -P "$IDEMPOTENCY_STORM" -I IDX \
  curl -s -X POST "$BASE_URL/transfers" -H "Authorization: Bearer $ATOK" \
  -H 'Content-Type: application/json' -d "$BODY" -o "$TMP/t.IDX.json"

DISTINCT_TX=$(cat "$TMP"/t.*.json | jq -r '.id' | sort -u | wc -l | tr -d ' ')
DISTINCT_RESP=$(cat "$TMP"/t.*.json | jq -cS '{id,status,amount_paise}' | sort -u | wc -l | tr -d ' ')
BAL_A=$(balance "$ATOK" "$A"); BAL_B=$(balance "$BTOK" "$B")
[ "$DISTINCT_TX" -eq 1 ]   && ok "one transfer id across all $IDEMPOTENCY_STORM responses" || bad "expected 1 transfer id, got $DISTINCT_TX"
[ "$DISTINCT_RESP" -eq 1 ] && ok "all responses identical" || bad "responses differ ($DISTINCT_RESP variants)"
[ "$BAL_A" -eq $((SEED_PAISE - XFER)) ] && ok "source debited exactly once (A=$BAL_A)" || bad "A debited wrong: expected $((SEED_PAISE - XFER)), got $BAL_A"
[ "$BAL_B" -eq "$XFER" ] && ok "destination credited exactly once (B=$BAL_B)" || bad "B credited wrong: expected $XFER, got $BAL_B"
echo

# ===========================================================================
echo "[3/4] Conservation under contention: $CONTENTION_TRANSFERS concurrent transfers among $CONTENTION_WALLETS wallets (incl. A->B and B->A)"
WALLETS=(); WTOKENS=()
for i in $(seq "$CONTENTION_WALLETS"); do
  TOK="cont-$i-$RANDOM"
  W=$(create_wallet "$TOK")
  deposit "$TOK" "$W" "$SEED_PAISE" "seed-$i-$RANDOM"
  WALLETS+=("$W"); WTOKENS+=("$TOK")
done
TOTAL_BEFORE=0
for idx in "${!WALLETS[@]}"; do TOTAL_BEFORE=$((TOTAL_BEFORE + $(balance "${WTOKENS[$idx]}" "${WALLETS[$idx]}"))); done

# Job list: "from to amount n from_owner_token". Random distinct pairs; a mix of
# amounts, some large enough to be declined. The caller token owns the `from`
# wallet, so the ownership check passes and the transfer is exercised.
: > "$TMP/jobs.txt"
for j in $(seq "$CONTENTION_TRANSFERS"); do
  fi=$((RANDOM % CONTENTION_WALLETS))
  ti=$((RANDOM % CONTENTION_WALLETS))
  while [ "$ti" -eq "$fi" ]; do ti=$((RANDOM % CONTENTION_WALLETS)); done
  amt=$(( (RANDOM % 3 + 1) * 20000 ))   # 20000 / 40000 / 60000 paise
  echo "${WALLETS[$fi]} ${WALLETS[$ti]} $amt $j ${WTOKENS[$fi]}" >> "$TMP/jobs.txt"
done

# Each request appends its HTTP status code; a 5xx means the server errored
# (e.g. a deadlock storm) rather than cleanly applying or declining the transfer.
: > "$TMP/status.txt"
xargs -P 32 -L1 bash -c '
  from="$1"; to="$2"; amt="$3"; n="$4"; tok="$5"
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "'"$BASE_URL"'/transfers" \
    -H "Authorization: Bearer $tok" -H "Content-Type: application/json" \
    -d "{\"from\":\"$from\",\"to\":\"$to\",\"amount_paise\":$amt,\"idempotency_key\":\"cont-$n-$RANDOM\"}")
  echo "$code" >> "'"$TMP"'/status.txt"
' _ < "$TMP/jobs.txt"

FIVEXX=$(grep -c '^5' "$TMP/status.txt" 2>/dev/null || true)
FIVEXX=${FIVEXX:-0}

TOTAL_AFTER=0; ANY_NEGATIVE=0
for idx in "${!WALLETS[@]}"; do
  b=$(balance "${WTOKENS[$idx]}" "${WALLETS[$idx]}")
  TOTAL_AFTER=$((TOTAL_AFTER + b))
  [ "$b" -lt 0 ] && ANY_NEGATIVE=1
done
[ "$TOTAL_AFTER" -eq "$TOTAL_BEFORE" ] && ok "total conserved (before=$TOTAL_BEFORE after=$TOTAL_AFTER)" || bad "total changed: before=$TOTAL_BEFORE after=$TOTAL_AFTER"
[ "$ANY_NEGATIVE" -eq 0 ] && ok "no wallet went negative" || bad "a wallet went negative"
[ "$FIVEXX" -eq 0 ] && ok "no 5xx under contention (no deadlock storm)" || bad "$FIVEXX requests returned 5xx (deadlock or server error)"
echo

# ===========================================================================
echo "[4/4] Authorization: a caller cannot move money out of a wallet they do not own"
VTOK="victim-$RANDOM"; V=$(create_wallet "$VTOK"); deposit "$VTOK" "$V" "$SEED_PAISE" "seed-v-$RANDOM"
ATTOK="attacker-$RANDOM"; ATT=$(create_wallet "$ATTOK")
# Attacker is authenticated (as themselves) but names the victim's wallet as `from`.
STEAL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $ATTOK" -H "Content-Type: application/json" \
  -d "{\"from\":\"$V\",\"to\":\"$ATT\",\"amount_paise\":5000,\"idempotency_key\":\"steal-$RANDOM\"}")
# Attacker also tries to read the victim's balance.
READ_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/wallets/$V" -H "Authorization: Bearer $ATTOK")
VBAL=$(balance "$VTOK" "$V")
[ "$STEAL_CODE" = "403" ] && ok "transfer from unowned wallet rejected (HTTP 403)" || bad "expected 403 on steal, got $STEAL_CODE"
[ "$READ_CODE" = "403" ]  && ok "reading another user's wallet rejected (HTTP 403)" || bad "expected 403 on read, got $READ_CODE"
[ "$VBAL" -eq "$SEED_PAISE" ] && ok "victim wallet untouched (balance=$VBAL)" || bad "victim balance changed: $VBAL"
echo

# ===========================================================================
echo "==================================================="
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ] && { green "ALL CHECKS PASSED"; exit 0; } || { red "CHECK FAILED"; exit 1; }
