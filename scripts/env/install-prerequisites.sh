#!/usr/bin/env bash
# One-time bootstrap for Linux/macOS: installs Docker, JDK 21, Maven and Node.js.
# Platform specific by necessity - it installs the Node runtime the other scripts need.
# Windows: scripts/env/install-prerequisites.ps1
# Afterwards run: node scripts/env/ensure-dev-env.mjs

set -euo pipefail

packages_apt="docker.io docker-compose-plugin openjdk-21-jdk maven nodejs npm"
packages_dnf="docker docker-compose-plugin java-21-openjdk-devel maven nodejs npm"
packages_brew="docker openjdk@21 maven node"

if command -v apt-get >/dev/null 2>&1; then
  echo "Installing with apt-get: $packages_apt"
  sudo apt-get update
  # shellcheck disable=SC2086
  sudo apt-get install -y $packages_apt
elif command -v dnf >/dev/null 2>&1; then
  echo "Installing with dnf: $packages_dnf"
  # shellcheck disable=SC2086
  sudo dnf install -y $packages_dnf
elif command -v brew >/dev/null 2>&1; then
  echo "Installing with brew: $packages_brew"
  # shellcheck disable=SC2086
  brew install $packages_brew
else
  echo "No supported package manager found (apt-get, dnf, brew)." >&2
  echo "Install manually: Docker, JDK 21, Maven, Node.js LTS." >&2
  exit 1
fi

echo
echo "Installed versions:"
docker --version || true
java -version || true
mvn -version | head -n 1 || true
node -v || true

echo
echo "Next: node scripts/env/ensure-dev-env.mjs"
