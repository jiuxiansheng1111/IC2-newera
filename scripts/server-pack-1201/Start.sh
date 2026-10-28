#!/usr/bin/env bash
set -eu
cd -- "$(dirname -- "$(readlink -f -- "$0")")"
exec ./start-server.sh "$@"
