#!/usr/bin/env bash
set -euo pipefail

json=false
require_tasks=false
include_tasks=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --json)
      json=true
      ;;
    --require-tasks)
      require_tasks=true
      ;;
    --include-tasks)
      include_tasks=true
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
  shift
done

test -d .specify
test -d specs
test -f .specify/templates/spec-template.md

feature_dir="${FEATURE_DIR:-}"
if [[ -z "$feature_dir" && -f .specify/feature.json ]]; then
  feature_dir="$(python3 -c 'import json; print(json.load(open(".specify/feature.json"))["feature"])')"
fi

if [[ -z "$feature_dir" ]]; then
  [[ "$json" == true ]] && printf '{}\n'
  exit 0
fi

spec_file="$feature_dir/spec.md"
plan_file="$feature_dir/plan.md"
tasks_file="$feature_dir/tasks.md"
test -f "$spec_file"
test -f "$plan_file"
if [[ "$require_tasks" == true ]]; then
  test -f "$tasks_file"
fi

if [[ "$json" == true ]]; then
  if [[ "$include_tasks" == true ]]; then
    printf '{"FEATURE_DIR":"%s","SPEC_FILE":"%s","PLAN_FILE":"%s","TASKS_FILE":"%s"}\n' "$feature_dir" "$spec_file" "$plan_file" "$tasks_file"
  else
    printf '{"FEATURE_DIR":"%s","SPEC_FILE":"%s","PLAN_FILE":"%s"}\n' "$feature_dir" "$spec_file" "$plan_file"
  fi
fi
