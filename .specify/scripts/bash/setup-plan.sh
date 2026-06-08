#!/usr/bin/env bash
set -euo pipefail

json=false
if [[ "${1:-}" == "--json" ]]; then
  json=true
fi

feature_dir="${FEATURE_DIR:-}"
if [[ -z "$feature_dir" && -f .specify/feature.json ]]; then
  feature_dir="$(python3 -c 'import json; print(json.load(open(".specify/feature.json"))["feature"])')"
fi
if [[ -z "$feature_dir" ]]; then
  echo "FEATURE_DIR is required when .specify/feature.json is absent" >&2
  exit 1
fi

spec_file="$feature_dir/spec.md"
plan_file="$feature_dir/plan.md"
test -f "$spec_file"
if [[ ! -f "$plan_file" ]]; then
  cp .specify/templates/plan-template.md "$plan_file"
fi

if [[ "$json" == true ]]; then
  printf '{"FEATURE_DIR":"%s","SPEC_FILE":"%s","PLAN_FILE":"%s"}\n' "$feature_dir" "$spec_file" "$plan_file"
else
  printf '%s\n' "$plan_file"
fi
