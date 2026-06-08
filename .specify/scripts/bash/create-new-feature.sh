#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 [--json] <description>" >&2
  exit 1
fi

json=false
if [[ "${1:-}" == "--json" ]]; then
  json=true
  shift
fi

description="$*"
short_name="$(printf '%s' "$description" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-//;s/-$//' | cut -c1-40)"
short_name="${short_name:-feature}"
number="001"
feature_dir="specs/${number}-${short_name}"
while [[ -e "$feature_dir" ]]; do
  number="$(printf '%03d' "$((10#$number + 1))")"
  feature_dir="specs/${number}-${short_name}"
done

mkdir -p "$feature_dir"
cp .specify/templates/spec-template.md "$feature_dir/spec.md"
printf '{"feature":"%s","description":"%s"}\n' "$feature_dir" "$description" > .specify/feature.json

if [[ "$json" == true ]]; then
  printf '{"FEATURE_DIR":"%s","SPEC_FILE":"%s"}\n' "$feature_dir" "$feature_dir/spec.md"
else
  printf '%s\n' "$feature_dir/spec.md"
fi
