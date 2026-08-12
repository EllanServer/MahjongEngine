#!/usr/bin/env bash
set -uo pipefail

if [ "$#" -lt 3 ] || [ "$2" != "--" ]; then
  echo "usage: $0 <log-file> -- <gradle-command> [args...]" >&2
  exit 2
fi

log_file="$1"
shift 2

for attempt in 1 2 3; do
  "$@" 2>&1 | tee "$log_file"
  status="${PIPESTATUS[0]}"
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if [ "$attempt" -eq 3 ] || ! grep -Eqi \
    '(repo\.maven\.apache\.org|plugins\.gradle\.org).*(status code (403|429|500|502|503|504)|too many requests)' \
    "$log_file"; then
    exit "$status"
  fi
  echo "Transient Maven repository response; retrying Gradle attempt $((attempt + 1))/3." >&2
  sleep "$((attempt * 15))"
done
