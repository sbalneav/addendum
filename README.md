# Addendum

**Phone forms for your plain-text files.**

Addendum is an Android app for people who keep parts of their life in plain text
files on their own server: a ledger journal, a calendar file, a reading log, a
todo list. You fill in a small form on your phone, and a script on your server
adds the result to the file.

The trick is that **the server script describes its own form.** To make a new
"app", you don't touch Android at all. You write a short shell script on the
server that says "I want a date, a time and a description", and that formats the
answers into a line of text. Then you add a profile for it on the phone. That's it.

```
field	when	date	Date	required	default=today
field	time	time	Time
field	what	text	What	required	hint=Dentist, haircut…
```

becomes a form with a date picker, a time picker and a text box, and the script
appends something like

```
Dec 03	Christmas dinner @ 18:00
```

to your `calendar` file.

## What you need

- A server you can SSH into from your phone: at home over WireGuard or
  Tailscale, a VPS, anything with `sshd` and `bash`.
- An Android phone (Android 8 or later).

## How it works

- The phone runs one SSH command per request, using a key generated on the
  phone. On the server, that key is locked to a single forced command, the
  `addendum` dispatcher. It can't open a shell or do anything else.
- The dispatcher runs **form scripts** from `~/.config/addendum/forms/`. Each
  one answers three questions: `spec` (what fields?), `add` (here are the
  values; write them) and `status` (what should the phone show under the form?).
- Field types: text, number, date, time, dropdown (searchable when long),
  radio buttons and checkbox.
- If the server can't be reached, records wait on the phone and are sent later.
  Each record carries an id, so a retry after a dropped connection can never
  add it twice.

Two example forms are included: **appointment** (BSD `calendar` files) and
**ledger** (expenses into a [ledger-cli](https://ledger-cli.org) journal). There's
also a **demo** form that uses every field type.

The full design, wire protocol and spec format are in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Setting up

1. **Server:** clone this repo anywhere and run `make deploy SERVER=yourhost`
   (or run `make install-server` on the server itself). See
   [server/README.md](server/README.md).
2. **Phone:** install the APK from the Releases page, or build it yourself with
   `./gradlew assembleRelease`.
3. In the app, open **Settings**, copy the phone's public key, and add it to
   `~/.ssh/authorized_keys` on the server like this:

   ```
   restrict,command="/home/YOU/bin/addendum" ecdsa-sha2-nistp256 AAAA... addendum-phone
   ```

4. **Add a profile:** enter your server and username, tap **Find forms on this
   server**, and pick one. You'll be asked to confirm the server's host key the
   first time.

## Writing your own form

A form is any executable that takes `spec`, `status` or `add` as its argument.
Here's a complete one that appends a timestamped note to `~/notes.txt`:

```bash
#!/bin/bash
set -euo pipefail
source "${ADDENDUM_LIB:-$HOME/.local/share/addendum/lib.sh}"

case "${1:-}" in
spec)
    printf 'title\tQuick note\n'
    printf 'field\tnote\ttext\tNote\trequired\n'
    printf 'status\n'
    ;;
status)
    tail -n 5 ~/notes.txt 2>/dev/null || echo "(no notes yet)"
    ;;
add)
    read_values                      # fills the array V from the phone's values
    need note                        # "note: required" shows under the field
    line="$(date '+%F %H:%M') ${V[note]}"
    append_line ~/notes.txt "$line"
    echo "$line"                     # shown on the phone as confirmation
    ;;
esac
```

Save it as `~/.config/addendum/forms/note`, make it executable, and it shows up
under **Find forms** on the phone. Exit with status 1 (`die`) to reject bad
input, or 2 (`fail`) for a temporary problem the phone should retry later.

## Credits

The idea, generalizing a single-purpose ledger app into a form-driven tool
for any text file, is Scott Balneaves'.

The code, tests and documentation were written by Claude (Anthropic's Claude
Opus 5.5, working through Claude Code) in conversation with Scott, who tested it
on his own phone and server. The copyright notice is in Scott's name because
someone has to hold it, and an AI can't.

## License

MIT. See [LICENSE](LICENSE).
