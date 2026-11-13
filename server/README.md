# Addendum: server setup

The server side is three pieces:

| Piece | Installed at | What it is |
|---|---|---|
| `addendum` | `~/bin/addendum` | the dispatcher, the SSH forced command for the phone's key |
| `lib.sh` | `~/.local/share/addendum/lib.sh` | helpers that form scripts source |
| `forms/*` | `~/.config/addendum/forms/` | one executable per form |

## Install

From a checkout on another machine (runs the tests first, then copies each
piece over SSH):

    make deploy SERVER=yourhost

To skip typing `SERVER=` every time, put `SERVER = yourhost` in a file called
`local.mk` next to the Makefile (it's git-ignored).

Or, from a checkout on the server itself: `make install-server`.

Requirements on the server: bash 4+, coreutils, `flock` (util-linux), and
`~/bin` on your PATH if you want to run `addendum` by hand. The example forms
also want `calendar` (appointment) and `ledger` (ledger; optional but
recommended, since it's used to check the journal after each entry).

`make deploy` overwrites the forms that are in this repo. Edit them here, not on
the server. Forms you write that aren't in the repo are left alone.

## Try it by hand

    addendum list
    addendum appointment spec
    addendum appointment status
    printf 'when\t2026-12-01\ntime\t09:30\nwhat\tDentist\n' | addendum appointment add

(That last one really does add to your calendar.) To try things out safely,
copy `examples/demo` into the forms directory: it writes to a scratch file.

## Let the phone in

Put the phone's public key (from the app's Settings) in `~/.ssh/authorized_keys`
on the server, with the forced command in front:

    restrict,command="/home/YOU/bin/addendum" ecdsa-sha2-nistp256 AAAA... addendum-phone

The key can then *only* run addendum, which can only run your form scripts.
It can't open a shell, forward ports, or read other files.

## Writing a form

A form is an executable that takes `spec`, `status` or `add` as `$1`. See
`forms/appointment` for a complete example, and ARCHITECTURE.md for the spec
format. `lib.sh` provides `read_values` (fills the array `V`), `need`,
`check_date`, `check_time`, `check_length`, `login_env`, `append_line`, and
`die`/`fail` (exit 1 for bad input, exit 2 to retry later).

Run `make test` after changing anything here.
