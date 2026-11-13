# Addendum: Architecture

A general-purpose "formatted text file appender" for Android. The phone shows a
simple form, and a script on the server turns the answers into a line (or a few
lines) of text and puts it in a file. Each kind of file gets its own small shell
script on the server. The phone needs no code changes for a new one.

The phone is deliberately dumb and the server script is in charge: the script
describes its own form, checks every value, and decides what gets written where.
The phone reaches the server over plain SSH with a key that's locked to one
forced command.

## Goal and scope

- One screen per form: fill in fields, press the button, and one record is added.
- A read-only text panel under the form, filled by the script (e.g. the next two
  weeks of appointments, or account balances).
- Several **profiles** on the phone, each one pointing at one form on one server.

Out of scope, on purpose: editing or deleting records, multi-page or conditional
forms, computed fields, browsing the file. Do those at the keyboard on the server.

## Pieces

```
 phone (Addendum app)                    your server
 ┌───────────────────────┐   SSH exec    ┌─────────────────────────────────────┐
 │ Profiles (host, user, │ ───────────▶  │ sshd, phone key forced to:          │
 │  port, form name)     │ "list"        │   ~/bin/addendum  (dispatcher)      │
 │ Form screen built     │ "<form> spec" │     - checks the form name          │
 │  from the spec        │ "<form> add"  │     - locking, duplicate-send check │
 │ Status panel          │ "<form> status│     - runs the form script:         │
 │ Offline outbox        │ ◀───────────  │   ~/.config/addendum/forms/<form>   │
 └───────────────────────┘ stdout/err+rc └─────────────────────────────────────┘
```

- **Dispatcher** (`server/addendum`): the SSH forced command for the phone's key.
  It's the only thing the key can run. It handles everything that's the same for
  every form, so form scripts stay tiny.
- **Form scripts** (`~/.config/addendum/forms/<name>`): one executable per form.
  Any language works; bash is expected. Examples live in `server/forms/`.
- **App** (`app/`): Kotlin + Jetpack Compose, SSH via JSch (the maintained
  mwiede fork). See "The app" below.

## Wire protocol (phone → dispatcher)

The phone runs one SSH exec per request. The command arrives in
`$SSH_ORIGINAL_COMMAND`:

| Command          | stdin          | stdout                                    |
|------------------|----------------|-------------------------------------------|
| `list`           | none           | one form name per line                    |
| `<form> spec`    | none           | the form description (below)              |
| `<form> status`  | none           | text for the status panel, shown as-is in a monospaced font |
| `<form> add`     | field values   | `OK`, then the text that was added        |

Form names must match `^[a-z0-9][a-z0-9_-]*$` and be an executable file in the
forms directory. Anything else is refused.

**Exit status**:
- 0: success
- 1: the request is wrong; don't retry. stderr holds a message for the user.
  If the message starts with `<field>: `, the app shows it under that field.
- 2: temporary problem on the server (file busy, disk full); the outbox retries later.

### Sending values (`add`)

Values arrive as one line per field, `name<TAB>value`, in any order. A field left
blank is sent with an empty value. The app removes tabs, newlines and other
control characters from values before sending, so a value is always one line.

The app also sends `_id<TAB><uuid>` with every record. The dispatcher uses it to
spot a resend after a dropped connection: if that id was already recorded for
this form, it answers `OK` and doesn't run the script again. Form scripts never
see `_id`.

Value formats:

| Type       | Sent as                  |
|------------|--------------------------|
| `text`     | the text, trimmed        |
| `number`   | digits with an optional `.` and decimals, no commas or currency signs |
| `date`     | `YYYY-MM-DD`             |
| `time`     | `HH:MM`, 24-hour         |
| `dropdown`, `radio` | the chosen value, exactly as the spec listed it |
| `checkbox` | `yes` or `no`            |

The script must still check every value. The phone is a convenience, not a
safeguard.

## The spec format

`spec` prints tab-separated lines. The first word on each line says what it is.
Lines starting with `#` and blank lines are ignored. It's designed to be easy to
write from bash with `printf`, and to need no quoting or escaping.

```
title	Appointment
button	Add appointment
field	when	date	Date	required	default=today
field	time	time	Time
field	what	text	What	required	hint=Dentist, Auto Haus…
status
```

### Directives

- `title<TAB>text`: the screen title. Defaults to the form name.
- `button<TAB>text`: the label on the send button. Defaults to "Add".
- `field<TAB>name<TAB>type<TAB>label[<TAB>attribute…]`: one input, shown in the
  order listed. `name` is what's sent back in `add` (`[a-z0-9_]+`, not `_id`).
- `choice<TAB>name<TAB>value[<TAB>label]`: one option for a `dropdown` or
  `radio` field, in order. `label` is shown if given, otherwise the value.
- `status`: this form has a status panel, so the app should call `<form> status`.

### Field types

`text`, `number`, `date`, `time`, `dropdown` (searchable when it has many
choices), `radio`, `checkbox`.

### Attributes

| Attribute       | Meaning |
|-----------------|---------|
| `required`      | can't be sent blank |
| `default=X`     | starting value. For dates, `today` means today on the phone. For `checkbox`, `yes` or `no`. |
| `remember`      | start with whatever was sent last time (e.g. "Paid from"). Overrides `default` once there's a last value. |
| `hint=X`        | placeholder text shown in an empty field |
| `decimals=N`    | `number` only: at most N decimal places |
| `keep`          | don't clear the field after a successful send (e.g. the date, when entering several at once) |

Unknown attributes and directives are ignored. This leaves room to add more
later without breaking old phones.

## Offline use

The app caches each profile's spec and its choice lists. If the server can't be
reached, the form still works from the cache. Records go into the outbox with
their `_id`, and they're sent when the server is back. That's the same
store-and-forward approach. If the script rejects a queued
record when it's finally sent (exit 1), the app shows it so it can be fixed or
thrown away.

## Profiles (phone side)

Each profile holds:
- a display name
- host, port, user
- the form name, picked from the `list` output

All profiles share the phone's single SSH key. Host keys are pinned per host.
Pressing ↻ fetches the spec and status again.

## Dispatcher duties

Everything that's the same for every form lives here:

1. Check the form name, and refuse anything else.
2. For `add`: read stdin, pull out `_id`, and check it against the form's
   recently-sent ids (`~/.local/state/addendum/<form>/sent-ids`).
3. Take a per-form lock (`flock`) around `add`, so two sends can't interleave.
4. Run the form script with the command as `$1` and the values (minus `_id`) on
   stdin. Pass its stdout, stderr and exit status straight back.
5. On success, record the `_id`.

Form scripts can source `server/lib.sh` (installed at `~/.local/share/addendum/lib.sh`) for
small helpers, like reading the values into an associative array and the
`die`/`fail` functions for exit 1/2.

## The app

| File | Role |
|------|------|
| `MainActivity.kt` | Compose host, theme, switches between the three screens (no nav library) |
| `ui/FormScreen.kt` | The form, built from the spec; the status card; the waiting-to-send card |
| `ui/SettingsScreen.kt` | Profiles, the phone's public key, trusted host keys; `HostKeyDialog` |
| `ui/ProfileScreen.kt` | Add/edit a profile; "Find forms" runs `list` |
| `MainViewModel.kt` | All UI state, runs remote calls on `Dispatchers.IO` |
| `Spec.kt` | Spec parser and form rules (defaults, validation, encoding). Plain Kotlin, unit-tested |
| `FormServer.kt` | Client side of the wire protocol |
| `Outbox.kt` | Store-and-forward queue and its WorkManager background sender |
| `Ssh.kt` | `SshClient` (one exec per call), the phone's key pair, pinned host keys |
| `Prefs.kt` | SharedPreferences: profiles, host keys, cached specs and status, remembered values |

**Security model.** The phone holds one ECDSA P-256 key, generated on the phone
and never leaving it. On the server, that key's `authorized_keys` line carries
`restrict` and `command="…/addendum"`, so it can't get a shell, forward ports or
run anything but the dispatcher. The dispatcher splits the requested command
without any shell expansion and only runs executables in the forms directory
whose names match the pattern above. Values only ever travel on stdin, never in
a command line. Host keys are pinned on first use, after the user compares the
fingerprint; a changed host key is refused.

## Example form: `appointment`

It adds to `$CALENDAR_DIR/calendar.appointments`, a BSD `calendar(1)` file
(the kind `calendar -A 14` reads).

| Field  | Type | Notes |
|--------|------|-------|
| `when` | date, required, default today | written as `Nov 24`, zero-padded (`Aug 03`) |
| `time` | time, optional | appended as ` @ 08:00` |
| `what` | text, required | |

The result is a line like `Nov 24<TAB>Dentist @ 08:00`.

- New lines are **appended to the end of the file**, which keeps it in date
  order when appointments are added for future dates. Put any vim modeline or
  other trailer at the *top* of the file. If the file doesn't end with a
  newline, the script adds one first.
- `status` runs `calendar -w -A 14`, so a new entry shows up in the panel straight away.
- `calendar` dates have no year, so appointments repeat every year until they're
  deleted by hand. The script never touches existing lines.
- `$CALENDAR_DIR` is usually exported in `~/.profile`, which SSH forced commands
  don't read; `login_env` fetches it from a login shell.

## Example form: `ledger`

It records one expense in a [ledger-cli](https://ledger-cli.org) journal (`$LEDGER_FILE`):

```
2026-11-03 Gas at Petro-Canada
        Expenses:Car:Fuel                   $52.30
        Liabilities:Mastercard
```

| Field      | Type | Notes |
|------------|------|-------|
| `payee`    | text, required | |
| `amount`   | number, required, 2 decimals | |
| `category` | dropdown (searchable), required | `Expenses:*` accounts in the Opening Balances entry |
| `source`   | radio, required, remember | `Assets:*` / `Liabilities:*` accounts in the same entry |
| `date`     | date, required, default today | |

- The entry goes at the end of `$LEDGER_FILE`, **uncleared**, to be marked when
  reconciling. The layout (ISO date, 8-space indent, account padded to 36) and
  the account patterns are settings at the top of the script.
- It locks the journal with `flock` while writing. If `ledger` is installed, it
  checks that the journal still parses afterwards and rolls the entry back if not.
- The status panel shows `ledger --depth=2 balance ^Assets ^Liabilities`.

## Later

- Maybe: multi-line text (it would need an escape for newlines).
