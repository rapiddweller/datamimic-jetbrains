#!/usr/bin/env bash
# DATAMIMIC for JetBrains IDEs
# Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
# SPDX-License-Identifier: MIT

set -euo pipefail

mode="${1:---check}"
repository="$(cd "$(dirname "$0")/.." && pwd)"
platform="${2:-$repository/../rd-svc-datamimic-platform}"
source_file="$platform/src/ide/assets/datamimic-agent-workflow.md"
target_file="$repository/src/main/resources/junie/datamimic-agent-workflow.md"

case "$mode" in
  --check)
    cmp -s "$source_file" "$target_file" || {
      echo "Junie agent guidance is stale; run $0 --write <platform-checkout>." >&2
      exit 1
    }
    ;;
  --write)
    cp "$source_file" "$target_file"
    ;;
  *)
    echo "Usage: $0 [--check|--write] [platform-checkout]" >&2
    exit 2
    ;;
esac
