#!/bin/sh
set -eu
version=0.5.11
archive="flyctl_${version}_Linux_x86_64.tar.gz"
install_dir="${RUNNER_TEMP:?}/bitbrush-fly"
mkdir -p "$install_dir"
cd "$install_dir"
curl --fail --silent --show-error --location --retry 3 \
  "https://github.com/superfly/flyctl/releases/download/v${version}/${archive}" -o "$archive"
curl --fail --silent --show-error --location --retry 3 \
  "https://github.com/superfly/flyctl/releases/download/v${version}/flyctl_${version}_checksums.txt" -o checksums.txt
# Verify precisely the archive we will execute, ignoring other release assets.
awk -v archive="$archive" '$2 == archive { print }' checksums.txt > selected-checksum.txt
[ "$(wc -l < selected-checksum.txt)" -eq 1 ]
sha256sum --check selected-checksum.txt
tar -xzf "$archive" flyctl
chmod +x flyctl
printf '%s\n' "$install_dir" >> "${GITHUB_PATH:?}"
