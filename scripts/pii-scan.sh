#!/usr/bin/env bash
# Refuse to let personal data reach a committed file (AGENTS.md §3).
#
# Scans tracked AND untracked-but-not-ignored files, so it tells the truth
# about what a commit would carry rather than only about what one already has.
#
# Two tiers, because a scanner nobody trusts is a scanner everybody disables:
#
#   HARD  credential shapes that are never a coincidence -- GitHub/OpenAI/
#         Anthropic/AWS/Google keys, JWTs (a Home Assistant long-lived token is
#         one), Tailscale keys, PEM private key blocks. Scanned everywhere, no
#         exemptions. A hit here is real; rotate the credential.
#
#   SOFT  things that are usually a fixture and occasionally a leak -- RFC1918
#         host addresses, MAC addresses, a password assigned a literal, GPS
#         coordinates. Scanned everywhere EXCEPT test paths, and skipped on
#         lines that are evidently an example (see $placeholder below).
#
# Binary files are searched as text as well and reported by name only. An
# editor's swap copy of .env is a binary file with the real address inside, and
# a scan that skips binary files calls it clean.
#
# Structurally exempt, by construction rather than by exception list:
#   - documentation ranges 192.0.2.x / 198.51.100.x / 203.0.113.x (use these in
#     tests and docs -- they are reserved for exactly this and never route)
#   - .0 and .255 host octets, so network/broadcast addresses and CIDR blocks
#     (10.0.0.0/8, 192.168.0.0/16) read as topology, not as someone's device
#   - 127.0.0.1, 0.0.0.0, the all-zero and all-ff MACs, and the textbook
#     aa:bb:cc:dd:ee:ff
#
# Run it before every push. It is also the pre-push hook and a CI job.
# Exit 0 = clean, 1 = something was found, 2 = usage/setup error.
#
# False positive? Add a narrow pathspec to scripts/pii-scan.exclude with a
# comment saying why. Never widen it to a whole directory, and never weaken a
# pattern below to make a push go through.
set -euo pipefail

root=$(git rev-parse --show-toplevel) || { echo "not a git repo" >&2; exit 2; }
cd "$root"

# This script and the hook quote the patterns themselves.
base=(':(exclude)scripts/pii-scan.sh' ':(exclude).githooks/pre-push')
if [[ -f scripts/pii-scan.exclude ]]; then
  while IFS= read -r line; do
    [[ -z $line || $line == \#* ]] && continue
    base+=(":(exclude)${line%%[[:space:]]#*}")
  done < scripts/pii-scan.exclude
fi

# Test fixtures are exempt from the SOFT patterns only. A real credential in a
# test is still a credential and is still caught by the HARD tier.
soft=("${base[@]}"
  ':(exclude)test' ':(exclude)tests' ':(exclude)*/test/*' ':(exclude)*/tests/*'
  ':(exclude)test/*' ':(exclude)tests/*'
  ':(exclude)*_test.*' ':(exclude)*.test.*' ':(exclude)*.spec.*' ':(exclude)*/testdata/*')

# A line that says "this is an example" is taken at its word.
placeholder='your_|<[A-Za-z0-9_-]+>|changeme|CHANGEME|placeholder|PLACEHOLDER|REPLACE_?ME|example|EXAMPLE|sample|dummy|fixture|xxxx|XXXX|\.\.\.'
mac_sentinel='00:00:00:00:00:00|[fF]{2}(:[fF]{2}){5}|[aA][aA]:[bB][bB]:[cC][cC]:[dD][dD]:[eE][eE]:[fF][fF]'

# Drop git-grep hits whose CONTENT matches the regex. Content only: a path such
# as tests/fixtures.py must never be what excuses a token on the line.
filter_content() {
  local re=$1 line body
  while IFS= read -r line; do
    body=${line#*:}; body=${body#*:}
    grep -qE -- "$re" <<<"$body" || printf '%s\n' "$line"
  done
}

fail=0
scan() { # <tier: hard|soft> <label> <pattern> [extra-skip-regex]
  local tier=$1 label=$2 pattern=$3 skip=${4:-} hits binary flags out
  local -a found=()
  local -n specs="${tier}_specs"
  # --untracked so a file that has not been `git add`ed yet is still
  # scanned. Without it this checks only tracked files, which means it
  # passes vacuously on a repo with no commits -- reporting "clean" while
  # examining nothing, at exactly the moment a first commit is most likely
  # to carry a mistake. Ignored files are still skipped, so .env stays out.
  # git grep exits 1 for "no match" and 128/129 when it could not search at
  # all (a bad pathspec, or a pattern it read as an option). Only 1 is clean:
  # a scan that examined nothing must never print "clean", so anything else
  # stops the scan. -e keeps a pattern that starts with "-" (the private-key
  # one does) a pattern, not an option.
  #
  # Three passes, so that "binary" means what git means by it: the matching
  # lines of text files (-I), the names of all files that match when binary
  # ones are read as text (-a), and the names of the text files among those.
  # A name in the second list and not in the third is a binary file.
  local rc
  for flags in -nI -la -lI; do
    rc=0
    out=$(git grep --untracked "$flags" -E -e "$pattern" -- . "${specs[@]}") || rc=$?
    if ((rc > 1)); then
      echo "pii-scan: git grep failed (exit $rc) while checking: $label" >&2
      exit 2
    fi
    found+=("$out")
  done
  hits=${found[0]}
  if [[ -n $skip && -n $hits ]]; then
    hits=$(printf '%s\n' "$hits" | filter_content "$skip")
  fi
  # No line to show for these, and no line that could say "this is an example".
  binary=$(comm -23 <(sort <<<"${found[1]}") <(sort <<<"${found[2]}"))
  if [[ -n $binary ]]; then
    hits+=${hits:+$'\n'}${binary//$'\n'/$': binary file matches\n'}': binary file matches'
  fi
  [[ -n $hits ]] || return 0
  printf '\n!! %s\n%s\n' "$label" "$hits"
  fail=1
}
# Reached by nameref from scan(), which shellcheck cannot follow.
# shellcheck disable=SC2034
hard_specs=("${base[@]}")
# shellcheck disable=SC2034
soft_specs=("${soft[@]}")

# --- hard --------------------------------------------------------------------
scan hard 'GitHub token' \
  '\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}|\bgithub_pat_[A-Za-z0-9_]{20,}' "$placeholder"
scan hard 'API key (OpenAI/Anthropic/AWS/Google)' \
  '\b(sk-[A-Za-z0-9_-]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35})\b' "$placeholder"
scan hard 'access token (JWT, e.g. a Home Assistant long-lived token)' \
  '\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' "$placeholder"
scan hard 'Tailscale key' \
  '\btskey-[a-z]+-[A-Za-z0-9]{6,}-[A-Za-z0-9]{10,}' "$placeholder"
scan hard 'private key material' \
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'

# --- soft --------------------------------------------------------------------
# Host octet is 1-254: .0 and .255 are network/broadcast, not a device.
o='([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])'
h='([1-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-4])'
scan soft 'private (RFC1918) host address' \
  "\\b(10\\.$o\\.$o\\.$h|172\\.(1[6-9]|2[0-9]|3[01])\\.$o\\.$h|192\\.168\\.$o\\.$h)\\b" "$placeholder"
scan soft 'MAC address' \
  '\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b' "$placeholder|$mac_sentinel"
scan soft 'password/secret assigned a literal' \
  '(password|passwd|secret|api_?key|token)[[:space:]]*[=:][[:space:]]*["'\''][^"'\''<$]{8,}' "$placeholder"
# A home's coordinates are its street address. Four decimals is house-level
# (~11 m): a lat/long key given one, or a bare "lat, long" pair. The pair's
# first number needs two digits and the comma a space after it, so scores like
# 0.1234, 0.5678 and SVG path data (12.345678,-12.34567) pass.
scan soft 'GPS coordinates' \
  '\b(lat|latitude|lon|lng|long|longitude)["'\'']?[[:space:]]*[:=][[:space:]]*["'\'']?-?[0-9]{1,3}\.[0-9]{4,}|\b[1-8][0-9]\.[0-9]{4,},[[:space:]]+-?[0-9]{1,3}\.[0-9]{4,}\b' "$placeholder"

if (( fail )); then
  cat >&2 <<'MSG'

Personal data or a credential is present in a file a commit would carry. Move
the value to .env (gitignored) and leave a placeholder behind. A binary file
that matches is usually an editor's swap or backup copy of a private file:
delete it or ignore it. If it is already pushed, rotate the credential; git
history keeps it forever.
MSG
  exit 1
fi

# Count the candidate set directly. `git grep -l ''` skips zero-byte files,
# which understates the number -- and a security tool that misreports what it
# examined is the whole failure this change exists to fix.
echo "pii-scan: clean ($(git ls-files --cached --others --exclude-standard | wc -l) files)"
