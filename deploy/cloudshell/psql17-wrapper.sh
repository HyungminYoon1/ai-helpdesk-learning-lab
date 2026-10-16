#!/usr/bin/env bash
set -euo pipefail
helpdesk_psql_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
exec env -u LD_PRELOAD -u LD_AUDIT \
    LD_LIBRARY_PATH="$helpdesk_psql_root/lib" \
    "$helpdesk_psql_root/bin/psql.real" "$@"
