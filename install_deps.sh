#!/usr/bin/env bash
# Installs the toolchains this repository needs to build and test on a fresh
# Debian/Ubuntu VM, or checks that they are present.
#
#   sudo ./install_deps.sh     install whatever is missing (safe to re-run)
#   ./install_deps.sh --check  report what is missing, exit 1 if anything is;
#                              needs no root and changes nothing
#
# What it sets up:
#   apt packages  git, curl, unzip, a C toolchain, python3 + venv, gh, docker,
#                 and JDK 21 unless one is already installed (Gradle needs
#                 JDK >= 17 to run; the app's unit tests run on Java 21)
#   Rust          rustup stable with rustfmt, clippy and the two musl targets
#                 the daemon is released for, installed for the invoking user
#                 and linked into /usr/local/bin
#   Gradle        the version CI uses, in /opt/gradle
#   Android SDK   command-line tools, platform and build-tools. An SDK that
#                 is already there is reused: ANDROID_HOME, then the one an
#                 earlier install recorded, then ~/Android/Sdk (Android
#                 Studio's default). Otherwise it goes in /opt/android-sdk.
#
# The linters used by scripts/static-checks.sh are not installed here: that
# script downloads its own pinned copies on first use.
#
# Keep the versions below in step with .github/workflows/ci.yml.
set -euo pipefail

GRADLE_VERSION=9.3.1
ANDROID_PLATFORM="platforms;android-36.1"
ANDROID_BUILD_TOOLS="build-tools;36.0.0"
ANDROID_CMDLINE_TOOLS_BUILD=13114758
MIN_JDK=17
# Robolectric needs this Java version for the unit tests. app/build.gradle.kts
# asks Gradle for it as a toolchain; keep the two in step.
UNIT_TEST_JDK=21
RUST_TARGETS=(x86_64-unknown-linux-musl aarch64-unknown-linux-musl)
RUST_COMPONENTS=(rustfmt clippy)
RUST_LINKS=(cargo rustc rustup rustfmt cargo-fmt cargo-clippy clippy-driver)
DEFAULT_ANDROID_HOME=/opt/android-sdk
PROFILE_SNIPPET=/etc/profile.d/agentsterm-dev.sh

usage() {
  echo "usage: sudo $0        install missing dependencies" >&2
  echo "       $0 --check     report missing dependencies" >&2
  exit 2
}

mode=install
case "${1:-}" in
  "") ;;
  --check) mode=check ;;
  *) usage ;;
esac
[ $# -le 1 ] || usage

script="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

# The user the toolchains are for: whoever ran sudo, or the current user.
target_user="${SUDO_USER:-$(id -un)}"
target_home="$(getent passwd "$target_user" | cut -d: -f6)"
target_home="${target_home:-$HOME}"

# Where the Android SDK is, or where to put it. Agent shells and sudo often
# lack ANDROID_HOME, so also look where an earlier install recorded it and in
# Android Studio's default location before settling on the install default.
find_android_home() {
  local recorded
  if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    printf '%s\n' "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    return
  fi
  if [ -r "$PROFILE_SNIPPET" ]; then
    recorded="$(sed -n 's/^export ANDROID_HOME="\(.*\)"$/\1/p' "$PROFILE_SNIPPET")"
    if [ -n "$recorded" ] && [ -d "$recorded" ]; then
      printf '%s\n' "$recorded"
      return
    fi
  fi
  if [ -d "$target_home/Android/Sdk" ]; then
    printf '%s\n' "$target_home/Android/Sdk"
    return
  fi
  printf '%s\n' "$DEFAULT_ANDROID_HOME"
}

android_home="$(find_android_home)"
export PATH="$target_home/.cargo/bin:$PATH"

# --- checks ------------------------------------------------------------------

missing=()
notes=()

ok() { printf '  ok       %s\n' "$1"; }
miss() {
  printf '  MISSING  %s\n' "$1"
  missing+=("$1")
}
note() { notes+=("$1"); }

has() { command -v "$1" >/dev/null 2>&1; }

jdk_major() {
  has javac || return 1
  javac -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p'
}

jdk_ok() {
  local major
  major="$(jdk_major)" || return 1
  [ -n "$major" ] && [ "$major" -ge "$MIN_JDK" ]
}

# A JDK for the unit tests that Gradle can find by itself: the default one, a
# distribution package in /usr/lib/jvm, or one Gradle downloaded earlier.
unit_test_jdk_ok() {
  local release
  [ "$(jdk_major)" = "$UNIT_TEST_JDK" ] && return 0
  for release in /usr/lib/jvm/*/release "$target_home"/.gradle/jdks/*/release; do
    [ -f "$release" ] || continue
    grep -q "^JAVA_VERSION=\"${UNIT_TEST_JDK}[.\"]" "$release" && return 0
  done
  return 1
}

python_ok() {
  has python3 || return 1
  has uv || python3 -c 'import ensurepip, venv' >/dev/null 2>&1
}

rust_targets_ok() {
  local installed target
  installed="$(rustup target list --installed 2>/dev/null)" || return 1
  for target in "${RUST_TARGETS[@]}"; do
    grep -qx "$target" <<<"$installed" || return 1
  done
}

gradle_ok() {
  has gradle && gradle --version 2>/dev/null | grep -qx "Gradle $GRADLE_VERSION"
}

android_dir() { # android_dir "platforms;android-36.1" -> <sdk>/platforms/android-36.1
  printf '%s/%s\n' "$android_home" "${1//;//}"
}

check_cmd() { # check_cmd <command> [label]
  if has "$1"; then ok "${2:-$1}"; else miss "${2:-$1}"; fi
}

check_that() { # check_that <label> <command...>
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then ok "$label"; else miss "$label"; fi
}

run_checks() {
  missing=()
  notes=()

  echo "Base tools"
  for tool in git curl unzip tar cc gh; do check_cmd "$tool"; done
  check_that "python3 with venv (or uv)" python_ok

  echo "JDK"
  check_that "JDK >= $MIN_JDK (javac)" jdk_ok
  if unit_test_jdk_ok; then
    ok "JDK $UNIT_TEST_JDK for the app unit tests"
  else
    printf '  absent   JDK %s for the app unit tests\n' "$UNIT_TEST_JDK"
    note "no JDK $UNIT_TEST_JDK found; Gradle downloads one into ~/.gradle/jdks the first time the app unit tests run (needs network access)."
  fi

  echo "Rust"
  for tool in rustup cargo rustc; do check_cmd "$tool"; done
  check_that "rustfmt" cargo fmt --version
  check_that "clippy" cargo clippy --version
  check_that "musl targets (${RUST_TARGETS[*]})" rust_targets_ok

  echo "Android"
  check_that "Gradle $GRADLE_VERSION" gradle_ok
  check_that "Android SDK $ANDROID_PLATFORM" test -d "$(android_dir "$ANDROID_PLATFORM")"
  check_that "Android SDK $ANDROID_BUILD_TOOLS" test -d "$(android_dir "$ANDROID_BUILD_TOOLS")"
  if [ -d "$android_home" ] && [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
    note "ANDROID_HOME is not set in this shell. Before running gradle: export ANDROID_HOME=$android_home"
  fi

  echo "Docker (only for the e2e/docker-vm tests)"
  if ! has docker; then
    printf '  absent   docker\n'
    note "docker is not installed; the e2e/docker-vm tests cannot run here."
  elif docker info >/dev/null 2>&1; then
    ok "docker"
  else
    printf '  no access  docker\n'
    note "docker is installed but this user cannot reach it (not in the docker group yet, or the daemon is stopped); the e2e/docker-vm tests cannot run here."
  fi
}

print_notes() {
  local n
  for n in "${notes[@]+"${notes[@]}"}"; do
    echo "note: $n"
  done
}

if [ "$mode" = check ]; then
  run_checks
  echo
  print_notes
  if [ ${#missing[@]} -gt 0 ]; then
    echo "${#missing[@]} required dependencies are missing."
    echo "Ask the user to run this from the repository root, then check again:"
    echo "    sudo ./install_deps.sh"
    exit 1
  fi
  echo "All required dependencies are installed."
  exit 0
fi

# --- install -----------------------------------------------------------------

if [ "$(id -u)" -ne 0 ]; then
  echo "Installing needs root. Run: sudo $0" >&2
  echo "(To only see what is missing: $0 --check)" >&2
  exit 1
fi
if ! has apt-get; then
  echo "This installer supports Debian/Ubuntu (apt) only." >&2
  echo "On other systems install the tools listed by: $0 --check" >&2
  exit 1
fi

step() { printf '\n==> %s\n' "$1"; }

as_user() {
  if [ "$target_user" = "$(id -un)" ]; then
    "$@"
  else
    sudo -H -u "$target_user" "$@"
  fi
}

fetch() { # fetch <url> <destination>
  echo "  downloading $1"
  curl -fsSL --retry 3 -o "$2" "$1"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

step "apt packages"
packages=(ca-certificates curl git unzip tar build-essential python3 python3-venv)
has gh || packages+=(gh)
has docker || packages+=(docker.io)
unit_test_jdk_ok || packages+=("openjdk-$UNIT_TEST_JDK-jdk-headless")
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq "${packages[@]}"
if getent group docker >/dev/null && ! id -nG "$target_user" | grep -qw docker; then
  usermod -aG docker "$target_user"
  echo "  added $target_user to the docker group (takes effect at next login)"
fi

step "Rust (rustup stable for $target_user)"
if ! has rustup; then
  fetch https://sh.rustup.rs "$tmp/rustup-init.sh"
  chmod 755 "$tmp" "$tmp/rustup-init.sh"
  as_user sh "$tmp/rustup-init.sh" -y --profile minimal --default-toolchain stable
fi
as_user "$target_home/.cargo/bin/rustup" toolchain install stable --no-self-update
as_user "$target_home/.cargo/bin/rustup" default stable
as_user "$target_home/.cargo/bin/rustup" component add "${RUST_COMPONENTS[@]}"
as_user "$target_home/.cargo/bin/rustup" target add "${RUST_TARGETS[@]}"
# Agent shells do not always load ~/.cargo/env, so make the tools visible on
# the default PATH as well.
for tool in "${RUST_LINKS[@]}"; do
  if [ -e "$target_home/.cargo/bin/$tool" ]; then
    ln -sfn "$target_home/.cargo/bin/$tool" "/usr/local/bin/$tool"
  fi
done

step "Gradle $GRADLE_VERSION"
if ! gradle_ok; then
  fetch "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" "$tmp/gradle.zip"
  mkdir -p /opt/gradle
  rm -rf "/opt/gradle/gradle-$GRADLE_VERSION"
  unzip -q "$tmp/gradle.zip" -d /opt/gradle
  ln -sfn "/opt/gradle/gradle-$GRADLE_VERSION/bin/gradle" /usr/local/bin/gradle
fi

step "Android SDK ($android_home)"
sdkmanager="$android_home/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
  if [ "$(uname -m)" != x86_64 ]; then
    echo "  warning: Google ships Linux Android build-tools for x86_64 only; app builds may not work on $(uname -m)." >&2
  fi
  fetch "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_BUILD}_latest.zip" "$tmp/cmdline-tools.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/android"
  mkdir -p "$android_home/cmdline-tools"
  rm -rf "$android_home/cmdline-tools/latest"
  mv "$tmp/android/cmdline-tools" "$android_home/cmdline-tools/latest"
fi
if [ ! -d "$(android_dir "$ANDROID_PLATFORM")" ] || [ ! -d "$(android_dir "$ANDROID_BUILD_TOOLS")" ]; then
  # `yes` exits with SIGPIPE once sdkmanager stops reading; that is expected.
  (yes || true) | "$sdkmanager" --sdk_root="$android_home" --licenses >/dev/null
  "$sdkmanager" --sdk_root="$android_home" "platform-tools" "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS"
fi
# Gradle writes licence and component files into the SDK, so the user owns it.
chown -R "$target_user": "$android_home"
cat >"$PROFILE_SNIPPET" <<EOF
# Written by install_deps.sh in the agents-term repository.
export ANDROID_HOME="$android_home"
export PATH="\$PATH:\$ANDROID_HOME/platform-tools"
EOF
chmod 644 "$PROFILE_SNIPPET"

step "Result"
# Check as the user who will do the work: rustup resolves its toolchain from
# that user's home directory.
if ! as_user env ANDROID_HOME="$android_home" bash "$script" --check; then
  echo "Install finished, but some dependencies are still missing (see above)." >&2
  exit 1
fi
echo "Open a new shell (or run: . $PROFILE_SNIPPET) to pick up ANDROID_HOME."
