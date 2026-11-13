#!/bin/bash
# test.sh: exercises the dispatcher and the appointment form against a
# throwaway calendar directory. Never touches real files.
#   ./server/test.sh            (from the repo root, on any machine with calendar(1))

set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

export ADDENDUM_FORMS="$here/forms"
export ADDENDUM_LIB="$here/lib.sh"
export XDG_STATE_HOME="$tmp/state"
export CALENDAR_DIR="$tmp/cal"
unset SSH_ORIGINAL_COMMAND
mkdir -p "$CALENDAR_DIR"
# An existing entry two months out, so it never shows in the 14-day status.
existing="$(date -d '+60 days' '+%b %d')"$'\tSomething later'
printf '/* vim: set noexpandtab shiftwidth=4 tabstop=4: */\n%s' "$existing" \
    > "$CALENDAR_DIR/calendar.appointments"          # note: no final newline
printf '#include <calendar.appointments>\n' > "$CALENDAR_DIR/calendar"

ft="$here/addendum"
pass=0 failed=0
check() {   # check DESCRIPTION EXPECTED_RC EXPECTED_OUTPUT_SUBSTRING -- command...
    local desc=$1 want_rc=$2 want=$3; shift 4
    local out rc=0
    out=$("$@" 2>&1) || rc=$?
    if [[ $rc == "$want_rc" && "$out" == *"$want"* ]]; then
        pass=$((pass + 1))
    else
        failed=$((failed + 1))
        printf 'FAIL: %s\n  rc=%s (wanted %s)\n  output: %s\n' "$desc" "$rc" "$want_rc" "$out"
    fi
}
add() { printf "$1" | "$ft" appointment add; }
tomorrow=$(date -d tomorrow +%F)
tomorrow_cal=$(date -d tomorrow '+%b %d')

check "list"                0 "appointment"          -- "$ft" list
check "spec"                0 $'field\twhen\tdate'   -- "$ft" appointment spec
check "spec via ssh"        0 "title"                -- env SSH_ORIGINAL_COMMAND="appointment spec" "$ft"
check "unknown form"        1 "no such form"         -- "$ft" nope spec
check "path traversal"      1 "no such form"         -- "$ft" ../forms/appointment spec
check "bad command"         1 "not allowed"          -- "$ft" appointment rm
check "extra words"         1 "not allowed"          -- env SSH_ORIGINAL_COMMAND="appointment spec; rm -rf ~" "$ft"
check "glob not expanded"   1 "not allowed"          -- env SSH_ORIGINAL_COMMAND="*" "$ft"
check "status empty"        0 "nothing in the next"  -- "$ft" appointment status

check "add with time"       0 "OK"                   -- add "when\t$tomorrow\ntime\t08:00\nwhat\t  Auto Haus \n_id\taaaaaaaa-1\n"
check "resend same id"      0 "already recorded"     -- add "when\t$tomorrow\nwhat\tAuto Haus\n_id\taaaaaaaa-1\n"
check "add without time"    0 "Dentist"              -- add "when\t$tomorrow\ntime\t\nwhat\tDentist\n_id\tbbbbbbbb-2\n"
check "status shows it"     0 "Auto Haus @ 08:00"    -- "$ft" appointment status

check "missing what"        1 "what: required"       -- add "when\t$tomorrow\n"
check "blank what"          1 "what: required"       -- add "when\t$tomorrow\nwhat\t   \n"
check "bad date"            1 "when: not a valid"    -- add "when\t2026-02-30\nwhat\tx\n"
check "bad time"            1 "time: not a valid"    -- add "when\t$tomorrow\ntime\t25:00\nwhat\tx\n"
check "control char"        1 "control characters"   -- add "when\t$tomorrow\nwhat\ta\tb\n"
check "bad id"              1 "bad id"               -- add "when\t$tomorrow\nwhat\tx\n_id\t;rm\n"
check "bad line"            1 "bad input line"       -- add "garbage\n"

expected=$'/* vim: set noexpandtab shiftwidth=4 tabstop=4: */\n'"$existing"$'\n'"$tomorrow_cal"$'\tAuto Haus @ 08:00\n'"$tomorrow_cal"$'\tDentist'
actual=$(cat "$CALENDAR_DIR/calendar.appointments")
if [[ "$actual" == "$expected" ]]; then pass=$((pass + 1)); else
    failed=$((failed + 1)); printf 'FAIL: file contents\n--- got:\n%s\n--- wanted:\n%s\n' "$actual" "$expected"
fi

# ---- ledger form, against a scratch journal ----
if command -v ledger >/dev/null; then
    export LEDGER_FILE="$tmp/test.ldg"
    cat > "$LEDGER_FILE" <<'EOF'
2026/01/01 Opening Balances
    Assets:Chequing                     $1000.00
    Liabilities:Mastercard              $-200.00
    Expenses:Food                          $0.00
    Expenses:Car:Fuel                      $0.00
    Equity:Opening Balances
EOF
    ladd() { printf "$1" | "$ft" ledger add; }
    check "ledger spec"         0 $'choice\tcategory\tExpenses:Car:Fuel'   -- "$ft" ledger spec
    check "ledger spec sources" 0 $'choice\tsource\tLiabilities:Mastercard' -- "$ft" ledger spec
    check "ledger status"       0 "Assets:Chequing"   -- "$ft" ledger status
    check "ledger add"          0 "Gas at Petro-Canada" -- \
        ladd "payee\tGas at Petro-Canada\namount\t52.3\ncategory\tExpenses:Car:Fuel\nsource\tLiabilities:Mastercard\ndate\t2026-11-13\n_id\tcccccccc-3\n"
    check "ledger resend"       0 "already recorded"  -- \
        ladd "payee\tGas at Petro-Canada\namount\t52.3\ncategory\tExpenses:Car:Fuel\nsource\tLiabilities:Mastercard\ndate\t2026-11-13\n_id\tcccccccc-3\n"
    check "ledger bad category" 1 "category: unknown" -- \
        ladd "payee\tX\namount\t1\ncategory\tExpenses:Nope\nsource\tAssets:Chequing\ndate\t2026-11-13\n"
    check "ledger missing"      1 "amount: required"  -- \
        ladd "payee\tX\ncategory\tExpenses:Food\nsource\tAssets:Chequing\ndate\t2026-11-13\n"
    check "ledger zero amount"  1 "amount: must be more" -- \
        ladd "payee\tX\namount\t0\ncategory\tExpenses:Food\nsource\tAssets:Chequing\ndate\t2026-11-13\n"
    check "ledger 3 decimals"   1 "amount: not a valid" -- \
        ladd "payee\tX\namount\t1.234\ncategory\tExpenses:Food\nsource\tAssets:Chequing\ndate\t2026-11-13\n"
    n=$(grep -c "Petro-Canada" "$LEDGER_FILE")
    if [[ $n == 1 ]] && grep -q 'Expenses:Car:Fuel *\$52.30' "$LEDGER_FILE"; then pass=$((pass + 1)); else
        failed=$((failed + 1)); printf 'FAIL: ledger file contents\n'; cat "$LEDGER_FILE"
    fi
else
    echo "(skipping ledger form tests: ledger isn't installed)"
fi

echo "$pass passed, $failed failed"
(( failed == 0 ))
