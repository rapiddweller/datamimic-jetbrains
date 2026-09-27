#!/usr/bin/env bash
# DATAMIMIC for JetBrains IDEs
# Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
# SPDX-License-Identifier: MIT

set -euo pipefail

semver_tag='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

latest_release_tag() {
  git tag --merged HEAD --list 'v*' --sort=-version:refname |
    awk -v pattern="$semver_tag" '$0 ~ pattern && !found { print; found = 1 }'
}

version_for() {
  local ref_type="$1" ref_name="$2" run_number="$3" latest_tag
  if [[ "$ref_type" == tag ]]; then
    if ! [[ "$ref_name" =~ $semver_tag ]]; then
      printf 'Tag %s is not v<major>.<minor>.<patch>\n' "$ref_name" >&2
      return 1
    fi
    printf '%s\n' "${ref_name#v}"
    return
  fi
  latest_tag="$(latest_release_tag)"
  if [[ -z "$latest_tag" ]]; then
    printf 'No reachable v<major>.<minor>.<patch> tag found\n' >&2
    return 1
  fi
  if ! [[ "$run_number" =~ ^[0-9]+$ ]]; then
    printf 'GitHub run number %s is not numeric\n' "$run_number" >&2
    return 1
  fi
  printf '%s-dev.%s\n' "${latest_tag#v}" "$run_number"
}

if [[ "${1:-}" == test ]]; then
  latest_tag="$(latest_release_tag)"
  [[ -n "$latest_tag" ]]
  [[ "$(version_for tag v7.8.9 42)" == 7.8.9 ]]
  [[ "$(version_for branch main 42)" == "${latest_tag#v}-dev.42" ]]
  ! version_for tag v0.2 42 >/dev/null 2>&1
  ! version_for tag v01.2.3 42 >/dev/null 2>&1
  exit
fi

version_for "${GITHUB_REF_TYPE:?}" "${GITHUB_REF_NAME:?}" "${GITHUB_RUN_NUMBER:?}"
