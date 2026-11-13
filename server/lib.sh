# lib.sh: helpers for Addendum form scripts. Source it at the top of a form:
#
#   source "${ADDENDUM_LIB:-$HOME/.local/share/addendum/lib.sh}"
#
# The dispatcher sets $ADDENDUM_LIB. Installed at ~/.local/share/addendum/lib.sh.

export LC_ALL=C.UTF-8

# die MESSAGE: the request is wrong; the phone shows MESSAGE and doesn't retry.
# Start the message with "<field>: " to show it under that field.
die()  { printf '%s\n' "$*" >&2; exit 1; }
# fail MESSAGE: temporary problem; the phone's outbox retries later.
fail() { printf '%s\n' "$*" >&2; exit 2; }

# read_values: read "name<TAB>value" lines from stdin into the array V.
declare -gA V=()
read_values() {
    local k v
    while IFS=$'\t' read -r k v || [[ -n "$k" ]]; do
        if [[ -n "$k" ]]; then V[$k]=$v; fi
    done
    return 0
}

# need FIELD...: each field must be present and not blank.
need() {
    local f
    for f; do [[ -n "${V[$f]:-}" ]] || die "$f: required"; done
}

# check_date FIELD: blank, or a real YYYY-MM-DD date.
check_date() {
    local v=${V[$1]:-}
    [[ -z "$v" ]] && return 0
    [[ "$v" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && date -d "$v" >/dev/null 2>&1 ||
        die "$1: not a valid date: $v"
}

# check_time FIELD: blank, or HH:MM (24-hour).
check_time() {
    local v=${V[$1]:-}
    [[ -z "$v" || "$v" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || die "$1: not a valid time: $v"
}

# check_length FIELD MAX: no longer than MAX characters.
check_length() {
    (( ${#V[$1]} <= $2 )) || die "$1: too long (at most $2 characters)"
}

# login_env NAME...: make sure these variables are set. SSH forced commands
# don't read ~/.profile or ~/.bashrc, so ask a login shell, then an
# interactive one, for anything that's missing.
login_env() {
    local name val
    for name; do
        [[ -n "${!name:-}" ]] && continue
        val=$(bash -lc "printf %s \"\${$name:-}\"" </dev/null 2>/dev/null || true)
        [[ -n "$val" ]] || val=$(bash -ic "printf %s \"\${$name:-}\"" </dev/null 2>/dev/null || true)
        [[ -n "$val" ]] || fail "$name is not set in the server environment"
        printf -v "$name" '%s' "$val"
        export "${name?}"
    done
}

# append_line FILE TEXT: add TEXT as the last line of FILE, first adding a
# newline if the file doesn't end with one.
append_line() {
    local file=$1 text=$2
    [[ -f "$file" ]] || fail "file not found: $file"
    if [[ -s "$file" && "$(tail -c 1 "$file" | od -An -c | tr -d ' ')" != '\n' ]]; then
        printf '\n' >> "$file" || fail "can't write to $file"
    fi
    printf '%s\n' "$text" >> "$file" || fail "can't write to $file"
}
