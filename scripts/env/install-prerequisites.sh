#!/usr/bin/env bash
# One-time bootstrap for Linux and macOS: installs what the MSaaS platform needs (Docker, JDK 21, Node.js 22) and, with --setup,
# continues with the setup of the repository (scripts/env/setup.mjs). Windows: scripts/env/install-prerequisites.ps1
#
#   bash scripts/env/install-prerequisites.sh --setup       install what is missing, then set the repository up
#   bash scripts/env/install-prerequisites.sh               only install what is missing
#   bash scripts/env/install-prerequisites.sh --check       only report what is installed; changes nothing
#   --yes       do not ask before installing    --dry-run   print what would be run, change nothing
#
# Safe to run again: what is installed is left alone. Maven is not installed: the repository's mvnw downloads the Maven it needs.
# Exit codes: 0 done, 1 something failed, 10 a log out / log in (Linux) or a first start of Docker Desktop (macOS) is needed first.
#
# Tested only through --dry-run and the unit tests of scripts/env; no real installation has been run on Linux or macOS.
# MSAAS_TEST_OS, MSAAS_TEST_PM and MSAAS_TEST_MISSING exist only for those tests.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NODE_MAJOR=22
CHECK=0 YES=0 DRY=0 SETUP=0
NEEDS_RELOGIN=0 NEEDS_DOCKER_START=0
ME="${USER:-$(id -un)}"

for arg in "$@"; do
  case "$arg" in
    --check) CHECK=1 ;;
    --yes) YES=1 ;;
    --dry-run) DRY=1 ;;
    --setup) SETUP=1 ;;
    -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 1 ;;
  esac
done

say() { printf '\n=== %s ===\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

have() {
  case ",${MSAAS_TEST_MISSING:-}," in *",$1,"*) return 1 ;; esac
  command -v "$1" >/dev/null 2>&1
}

# Every command that changes the machine goes through here, so --dry-run shows it instead of running it.
run() {
  if [ "$DRY" = 1 ]; then printf '+ %s\n' "$*"; else "$@"; fi
}

confirm() {
  [ "$YES" = 1 ] && return 0
  [ "$DRY" = 1 ] && return 0
  [ -t 0 ] || fail "$1 - run again with --yes to allow it (there is no terminal to ask on)"
  read -r -p "$1 [y/N] " answer
  case "$answer" in y|Y|yes|YES) return 0 ;; *) fail "not confirmed: $1" ;; esac
}

OS="${MSAAS_TEST_OS:-$(uname -s)}"
case "$OS" in
  Linux|Darwin) ;;
  *) fail "this script is for Linux and macOS; on Windows run scripts\\env\\install-prerequisites.ps1" ;;
esac

if [ -n "${MSAAS_TEST_PM:-}" ]; then PM="$MSAAS_TEST_PM"
elif have apt-get; then PM=apt
elif have dnf; then PM=dnf
elif have brew; then PM=brew
else PM=none
fi

SUDO=""
if [ "$PM" != brew ] && [ "$(id -u)" != 0 ]; then
  have sudo || [ "$DRY" = 1 ] || fail "sudo is needed to install packages; install it or run this script as root"
  SUDO="sudo"
fi

APT_UPDATED=0
pkg_install() {
  case "$PM" in
    apt)
      [ "$APT_UPDATED" = 1 ] || { run $SUDO apt-get update; APT_UPDATED=1; }
      run $SUDO apt-get install -y "$@" ;;
    dnf) run $SUDO dnf install -y "$@" ;;
    brew) run brew install "$@" ;;
    *) fail "no supported package manager (apt-get, dnf, brew): install Docker, JDK 21 and Node.js $NODE_MAJOR by hand (see TLDR.md, 'Install by hand')" ;;
  esac
}

jdk21_home() {
  local candidate output
  case ",${MSAAS_TEST_MISSING:-}," in *",java,"*) return 1 ;; esac
  for candidate in "${JAVA_HOME:-}" /usr/lib/jvm/*21* "$HOME"/.sdkman/candidates/java/21* "$HOME"/.local/jdks/*21* \
      /Library/Java/JavaVirtualMachines/*21*/Contents/Home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
      /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] || continue
    # captured first: "java -version | grep -q" would die of SIGPIPE under pipefail when grep stops reading early
    output="$("$candidate/bin/java" -version 2>&1 || true)"
    case "$output" in *'version "21.'*) echo "$candidate"; return 0 ;; esac
  done
  return 1
}

# Angular 19 runs on Node 20.11+ and 22+; the CI uses 22.
node_supported() {
  have node || return 1
  local version major minor
  version="$(node -v | sed 's/^v//')"
  major="${version%%.*}"; minor="${version#*.}"; minor="${minor%%.*}"
  [ "$major" -ge 22 ] || { [ "$major" -eq 20 ] && [ "$minor" -ge 11 ]; }
}

in_docker_group() {
  case " $(id -nG) " in *" docker "*) return 0 ;; *) return 1 ;; esac
}

say "Checking the machine"
echo "System: $OS, package manager: $PM"
JDK_HOME="$(jdk21_home || true)"
echo "Docker  : $(have docker && echo installed || echo missing)"
echo "JDK 21  : ${JDK_HOME:-missing}"
echo "Node.js : $(node_supported && node -v || echo 'missing or too old (needs 20.11+)')"
if [ "$CHECK" = 1 ]; then
  if have docker && [ -n "$JDK_HOME" ] && node_supported; then echo "All prerequisites are installed."; exit 0; fi
  echo "Something is missing: run this script without --check."; exit 1
fi

say "Installing what is missing"
if [ "$PM" = none ] && { ! have docker || [ -z "$JDK_HOME" ] || ! node_supported; }; then
  fail "no supported package manager (apt-get, dnf, brew): install Docker, JDK 21 and Node.js $NODE_MAJOR by hand (see TLDR.md, 'Install by hand')"
fi

if [ "$OS" = Linux ] && ! have curl; then
  case "$PM" in
    apt) pkg_install curl ca-certificates ;;
    dnf) pkg_install curl ca-certificates ;;
  esac
fi

# --- Docker ---
if ! have docker; then
  if [ "$OS" = Darwin ]; then
    [ "$PM" = brew ] || fail "Homebrew is needed on macOS: https://brew.sh"
    confirm "Install Docker Desktop with Homebrew (brew install --cask docker)?"
    run brew install --cask docker
    NEEDS_DOCKER_START=1
  else
    # Docker's own installer: the distributions' packages differ too much (the Compose plugin is missing from some).
    # It is downloaded to a file first, so it can be read before it runs as root.
    confirm "Install Docker Engine with Docker's official script (https://get.docker.com), run as root?"
    run curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    run $SUDO sh /tmp/get-docker.sh
    have systemctl && run $SUDO systemctl enable --now docker
    NEEDS_RELOGIN=1
  fi
fi
if [ "$OS" = Linux ] && have docker && ! docker info >/dev/null 2>&1 && ! in_docker_group; then
  confirm "Add $ME to the docker group (needed to use Docker without sudo)?"
  run $SUDO usermod -aG docker "$ME"
  NEEDS_RELOGIN=1
fi

# --- JDK 21 ---
if [ -z "$JDK_HOME" ]; then
  case "$PM" in
    apt) pkg_install openjdk-21-jdk ;;
    dnf) pkg_install java-21-openjdk-devel ;;
    brew) pkg_install openjdk@21 ;;
  esac
  if [ "$DRY" != 1 ] && [ -z "$(jdk21_home || true)" ]; then
    fail "JDK 21 is not available from this system's packages. Install Temurin 21 from https://adoptium.net/temurin/releases/?version=21 (unpack it to ~/.local/jdks) and run this again"
  fi
fi

# --- Node.js ---
if ! node_supported; then
  if [ "$PM" = brew ]; then
    pkg_install node
  else
    # The distributions' nodejs is often too old (Ubuntu 22.04 has 12): the official build, verified against its published checksum.
    case "$(uname -m)" in x86_64|amd64) ARCH=x64 ;; aarch64|arm64) ARCH=arm64 ;; *) fail "unsupported processor: $(uname -m)" ;; esac
    BASE="https://nodejs.org/dist/latest-v${NODE_MAJOR}.x"
    NODE_DIR="$HOME/.local"
    if [ "$DRY" = 1 ]; then
      echo "+ curl -fsSL $BASE/SHASUMS256.txt   (find node-v${NODE_MAJOR}.x.y-linux-$ARCH.tar.gz)"
      echo "+ curl -fsSL $BASE/<that file> -o /tmp/node.tar.gz   (checked with sha256sum)"
      echo "+ tar -xzf /tmp/node.tar.gz -C $NODE_DIR   then ln -sfn $NODE_DIR/node-v${NODE_MAJOR}.x.y-linux-$ARCH $NODE_DIR/node"
    else
      confirm "Install Node.js $NODE_MAJOR from nodejs.org into $NODE_DIR/node (no root needed)?"
      mkdir -p "$NODE_DIR"
      LINE="$(curl -fsSL "$BASE/SHASUMS256.txt" | grep -E "node-v[0-9.]+-linux-$ARCH\.tar\.gz\$" | head -n 1)"
      [ -n "$LINE" ] || fail "could not find the Node.js $NODE_MAJOR build for linux-$ARCH in $BASE/SHASUMS256.txt"
      FILE="${LINE##* }"
      curl -fsSL "$BASE/$FILE" -o "/tmp/$FILE"
      (cd /tmp && echo "$LINE" | sha256sum -c -) || fail "the download of $FILE does not match its published checksum"
      tar -xzf "/tmp/$FILE" -C "$NODE_DIR"
      ln -sfn "$NODE_DIR/${FILE%.tar.gz}" "$NODE_DIR/node"
      rm -f "/tmp/$FILE"
      export PATH="$NODE_DIR/node/bin:$PATH"
      PROFILE="$HOME/.profile"
      if ! grep -qs 'MSaaS: node' "$PROFILE" 2>/dev/null; then
        printf '\n# MSaaS: node\nexport PATH="$HOME/.local/node/bin:$PATH"\n' >> "$PROFILE"
        echo "Added $NODE_DIR/node/bin to PATH in $PROFILE (new terminals pick it up)."
      fi
    fi
  fi
fi

say "Result"
echo "Docker  : $(have docker && docker --version || echo missing)"
echo "JDK 21  : $(jdk21_home || echo '(not found)')"
echo "Node.js : $(have node && node -v || echo missing)"

if [ "$NEEDS_RELOGIN" = 1 ]; then
  echo
  echo "Docker was set up for your user. Log out and log in again (or restart), then run this script again."
  echo "(Until then 'docker' says permission denied.)"
  exit 10
fi
if [ "$NEEDS_DOCKER_START" = 1 ]; then
  echo
  echo "Docker Desktop was just installed: open it once (open -a Docker), accept its terms, wait until it says the engine is running, then run this script again."
  exit 10
fi

if [ "$SETUP" = 1 ]; then
  say "Setting up the repository"
  [ "$DRY" = 1 ] && { echo "+ node scripts/env/setup.mjs"; exit 0; }
  cd "$REPO_ROOT"
  exec node scripts/env/setup.mjs
fi

echo
echo "Prerequisites are in place. Next, from the repository root: node scripts/env/setup.mjs"
