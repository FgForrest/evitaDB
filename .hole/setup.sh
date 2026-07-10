#!/usr/bin/env bash
#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2026
#
#   Licensed under the Business Source License, Version 1.1 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

#
# Installs the latest Apache Maven 3.x release into /opt and puts it ahead of
# apt's (older) mvn on PATH via /usr/local/bin. evitaDB's build needs a newer
# Maven 3.x than what Ubuntu's apt package ships.
#
# Runs as the "agent" user during the Hole image build (passwordless sudo is
# available). Never install into $HOME here: it's backed by a persistent
# volume at sandbox runtime, so anything written here during the build would
# be invisible once the container starts.
set -euo pipefail

MAVEN_DIR_URL="https://dlcdn.apache.org/maven/maven-3/"
INSTALL_ROOT="/opt"
BIN_LINK="/usr/local/bin/mvn"

workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

echo "==> Resolving latest Maven 3.x release"
index_html="$(curl -fsSL --retry 5 --retry-delay 2 "${MAVEN_DIR_URL}")"
version="$(grep -oE 'href="3\.[0-9]+\.[0-9]+/"' <<<"${index_html}" \
  | sed -E 's#href="(.*)/"#\1#' \
  | sort -V \
  | tail -n1)"

if [ -z "${version}" ]; then
  echo "ERROR: could not determine latest Maven 3.x version from ${MAVEN_DIR_URL}" >&2
  exit 1
fi
echo "==> Latest Maven 3.x is ${version}"

archive="apache-maven-${version}-bin.tar.gz"
base_url="https://dlcdn.apache.org/maven/maven-3/${version}/binaries"

echo "==> Downloading ${archive}"
curl -fsSL --retry 5 --retry-delay 2 "${base_url}/${archive}" -o "${workdir}/${archive}"
curl -fsSL --retry 5 --retry-delay 2 "${base_url}/${archive}.sha512" -o "${workdir}/${archive}.sha512"

echo "==> Verifying checksum"
expected="$(tr -d '[:space:]' < "${workdir}/${archive}.sha512")"
actual="$(sha512sum "${workdir}/${archive}" | awk '{print $1}')"
if [ "${expected}" != "${actual}" ]; then
  echo "ERROR: sha512 mismatch for ${archive}" >&2
  echo "  expected: ${expected}" >&2
  echo "  actual:   ${actual}" >&2
  exit 1
fi

echo "==> Installing to ${INSTALL_ROOT}/apache-maven-${version}"
sudo tar -xzf "${workdir}/${archive}" -C "${INSTALL_ROOT}"
sudo ln -sfn "${INSTALL_ROOT}/apache-maven-${version}" "${INSTALL_ROOT}/maven"
sudo ln -sfn "${INSTALL_ROOT}/maven/bin/mvn" "${BIN_LINK}"

echo "==> Installed:"
"${BIN_LINK}" -version
