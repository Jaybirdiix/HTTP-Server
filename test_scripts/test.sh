#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-6789}"

normalize() { sed 's/\r$//'; }
status_line() { normalize | head -n 1; }

headers_only() {
  awk 'BEGIN{h=1} { sub(/\r$/,"") } h && $0=="" { exit } h { print }'
}

body_only_text() {
  awk 'BEGIN{h=1} { sub(/\r$/,"") } h && $0=="" { h=0; next } !h { print }'
}

ok()   { :; }
warn() { :; }

# Reads HTTP request from stdin, sends to HOST:PORT, prints full response.
# Arg3 = raw_mode (0/1). If raw_mode==1, send bytes exactly as provided.
send_request_bytes() {
  python3 -c '
import socket, sys
host, port = sys.argv[1], int(sys.argv[2])
raw_mode = bool(int(sys.argv[3]))

req = sys.stdin.buffer.read()

if not raw_mode:
    # Normalize line endings to CRLF
    req = req.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")

    # Ensure request ends with CRLFCRLF
    if not req.endswith(b"\r\n\r\n"):
        if req.endswith(b"\r\n"):
            req += b"\r\n"
        else:
            req += b"\r\n\r\n"

s = socket.create_connection((host, port))
s.settimeout(6.0)  # prevents hangs on intentionally malformed requests

s.sendall(req)
try:
    s.shutdown(socket.SHUT_WR)  # signal end-of-request (helps for weird cases)
except OSError:
    pass

resp = b""
while True:
    try:
        chunk = s.recv(65536)
    except socket.timeout:
        break
    if not chunk:
        break
    resp += chunk

s.close()
sys.stdout.buffer.write(resp)
' "$HOST" "$PORT" "${1:-0}"
}

# Binary-safe: return first N bytes of body as hex (lowercase)
body_prefix_hex() {
  local nbytes="${1:-16}"
  python3 -c '
import sys
n = int(sys.argv[1])
data = sys.stdin.buffer.read()
sep = data.find(b"\r\n\r\n")
if sep != -1:
    body = data[sep+4:]
else:
    sep = data.find(b"\n\n")
    body = data[sep+2:] if sep != -1 else b""
sys.stdout.write(body[:n].hex())
' "$nbytes"
}

run_case() {
  local file="${1:?run_case needs a filename}"
  local base; base="$(basename "$file")"
  echo "Running $base"

  local expect_status=""
  declare -a expect_headers=()
  declare -a expect_bodies=()
  local expect_magic_hex=""
  local raw_request=0

  # Collect expectations + directives
  while IFS= read -r line; do
    case "$line" in
      "# REQUEST-RAW"*) raw_request=1 ;;
      "# REQUEST-NORMALIZE"*) raw_request=0 ;;
      "# EXPECT-STATUS:"*)
        expect_status="${line#\# EXPECT-STATUS: }"
        ;;
      "# EXPECT-HEADER:"*)
        expect_headers+=("${line#\# EXPECT-HEADER: }")
        ;;
      "# EXPECT-BODY:"*)
        expect_bodies+=("${line#\# EXPECT-BODY: }")
        ;;
      "# EXPECT-MAGIC-HEX:"*)
        expect_magic_hex="$(echo "${line#\# EXPECT-MAGIC-HEX: }" | tr -d '[:space:]' | tr 'A-F' 'a-f')"
        ;;
    esac
  done < "$file"

  # Extract request part
  local req
  if [[ "$raw_request" -eq 1 ]]; then
    # RAW: remove meta lines only; keep *everything else*, including leading blank lines
    req="$(
      awk '
        /^[[:space:]]*# (EXPECT-(STATUS|HEADER|BODY|MAGIC-HEX):|REQUEST-(RAW|NORMALIZE))/ { next }
        { print }
      ' "$file"
    )"
  else
    # NORMALIZED: your original behavior (skip leading blank lines before request)
    req="$(
      awk '
        BEGIN{inreq=0; started=0}
        /^[[:space:]]*# (EXPECT-(STATUS|HEADER|BODY|MAGIC-HEX):|REQUEST-(RAW|NORMALIZE))/ { next }
        inreq==0 && $0 ~ /^[[:space:]]*$/ { next }
        inreq==0 { inreq=1 }
        started==0 && $0 ~ /^[[:space:]]*$/ { next }
        { started=1; print }
      ' "$file"
    )"

    # Keep your "default GET line" ONLY in normalized mode
    if [[ "$req" != *$'\n'* ]] && [[ "$req" != *HTTP/* ]]; then
      req=$'GET / HTTP/1.1\n'"$req"
    fi
  fi

  local resp
  resp="$(printf '%s' "$req" | send_request_bytes "$raw_request")"

  local failed=0

  # Status assertion
  if [[ -n "$expect_status" ]]; then
    local line
    line="$(printf '%s' "$resp" | status_line)"
    if printf '%s' "$line" | grep -qE "HTTP/1\.[01] ${expect_status}\b"; then
      ok "$base: status ${expect_status}"
    else
      echo "---- status ----"
      echo "$line"
      warn "$base: expected status ${expect_status}"
      failed=1
    fi
  fi

  # Header assertions
  for h in "${expect_headers[@]-}"; do
    [[ -z "$h" ]] && continue
    if printf '%s' "$resp" | headers_only | normalize | grep -qiF "$h"; then
      ok "$base: header contains '$h'"
    else
      echo "---- headers ----"
      printf '%s' "$resp" | headers_only | head -n 40
      warn "$base: missing header '$h'"
      failed=1
    fi
  done

  # Body substring assertions (text-oriented)
  for b in "${expect_bodies[@]-}"; do
    [[ -z "$b" ]] && continue
    if printf '%s' "$resp" | body_only_text | grep -qF "$b"; then
      ok "$base: body contains '$b'"
    else
      echo "---- body ----"
      printf '%s' "$resp" | body_only_text | head -n 60
      warn "$base: body missing '$b'"
      failed=1
    fi
  done

  # Optional: binary magic bytes check
  if [[ -n "$expect_magic_hex" ]]; then
    local got
    got="$(printf '%s' "$resp" | body_prefix_hex 16)"
    if [[ "$got" == "$expect_magic_hex"* ]]; then
      ok "$base: magic hex starts with $expect_magic_hex"
    else
      warn "$base: expected magic hex $expect_magic_hex but got $got"
      failed=1
    fi
  fi

  if [[ "$failed" -eq 0 ]]; then
    echo "✅ $base"
    return 0
  else
    echo "❌ $base"
    return 1
  fi
}

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <casefile1> [casefile2 ...]"
  exit 2
fi

total=0
failed_total=0

for f in "$@"; do
  total=$((total+1))
  if ! run_case "$f"; then
    failed_total=$((failed_total+1))
  fi
  echo
done

if [[ "$failed_total" -eq 0 ]]; then
  echo "🎉 All $total tests passed."
  exit 0
else
  echo "⚠️  $failed_total / $total tests failed."
  exit 1
fi
