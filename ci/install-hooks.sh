#!/bin/sh
set -eu
root=$(git rev-parse --show-toplevel)
cd "$root"
# Includes global/system configuration: never replace an effective hooks path.
if git config --get core.hooksPath >/dev/null; then
    echo 'Hook installation refused: core.hooksPath already configured. See README for manual integration.' >&2
    exit 1
fi
active=$(git rev-parse --git-path hooks/pre-push)
if [ -x "$active" ]; then
    echo 'Hook installation refused: active pre-push exists. See README for manual integration.' >&2
    exit 1
fi
[ -x .githooks/pre-push ] || { echo 'Repository hook missing or not executable.' >&2; exit 1; }
git config --local core.hooksPath .githooks
echo 'Installed optional main-push confirmation for this checkout.'
