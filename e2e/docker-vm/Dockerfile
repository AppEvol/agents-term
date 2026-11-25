# Multi-distro Linux VM E2E targets for testing the static Rust daemon across
# popular Linux VM environments without involving the Android app.
#
# Available targets (`docker build --target <name>`):
#   vm-ubuntu-2404     Ubuntu 24.04 LTS (dash, curl, cron, zero git/python3)
#   vm-minimal         Alias for vm-ubuntu-2404 (used by Android Emulator E2E)
#   vm-ubuntu-2204     Ubuntu 22.04 LTS (dash, curl, cron, zero git/python3)
#   vm-debian-12       Debian 12 Bookworm (dash, wget-only/no curl, cron, zero git/python3)
#   vm-almalinux-9     AlmaLinux 9 / RHEL 9 compatible (bash, curl, cronie, zero git)
#   vm-fedora-41       Fedora 41 (bash, curl, no cron -> detached-only fallback, zero git)
#   vm-amazonlinux-2023 Amazon Linux 2023 / AWS EC2 (bash, curl, no cron, zero git)
#   vm-alpine-321      Alpine Linux 3.21 (musl libc, BusyBox ash, wget-only/no curl, zero git/python3)
#   vm-legacy-python   Ubuntu 24.04 + git + python3 (live Python -> Rust upgrade)

# ---------------------------------------------------------------------------
# 1. Ubuntu 24.04 LTS (zero git, zero python3, curl + dash + Vixie cron)
# ---------------------------------------------------------------------------
FROM ubuntu:24.04 AS vm-ubuntu-2404

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    busybox-static \
    ca-certificates \
    cron \
    curl \
    openssh-server \
    procps \
    socat \
    && rm -rf /var/lib/apt/lists/* \
    && ! command -v git \
    && ! command -v python3

RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN mkdir -p /etc/ssh/sshd_config.d \
    && printf '%s\n' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication yes' \
      'PermitRootLogin no' \
      'UsePAM yes' \
      'AllowTcpForwarding yes' \
      'ClientAliveInterval 30' \
      > /etc/ssh/sshd_config.d/99-agentsterm-e2e.conf

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]

# Backward-compatible alias used by Android Emulator E2E and existing docs.
FROM vm-ubuntu-2404 AS vm-minimal

# Legacy VM target with Python 3 + git for testing live Python -> Rust daemon upgrades.
FROM vm-ubuntu-2404 AS vm-legacy-python

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    python3 \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# 2. Ubuntu 22.04 LTS (zero git, zero python3, curl + dash + Vixie cron)
# ---------------------------------------------------------------------------
FROM ubuntu:22.04 AS vm-ubuntu-2204

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    busybox-static \
    ca-certificates \
    cron \
    curl \
    openssh-server \
    procps \
    socat \
    && rm -rf /var/lib/apt/lists/* \
    && ! command -v git \
    && ! command -v python3

RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN mkdir -p /etc/ssh/sshd_config.d \
    && printf '%s\n' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication yes' \
      'PermitRootLogin no' \
      'UsePAM yes' \
      'AllowTcpForwarding yes' \
      'ClientAliveInterval 30' \
      > /etc/ssh/sshd_config.d/99-agentsterm-e2e.conf

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]

# ---------------------------------------------------------------------------
# 3. Debian 12 Bookworm (zero git, zero python3, wget-only / NO curl + cron)
# ---------------------------------------------------------------------------
FROM debian:12-slim AS vm-debian-12

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    busybox-static \
    ca-certificates \
    cron \
    openssh-server \
    procps \
    socat \
    wget \
    && rm -rf /var/lib/apt/lists/* \
    && ! command -v git \
    && ! command -v python3 \
    && ! command -v curl

RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN mkdir -p /etc/ssh/sshd_config.d \
    && printf '%s\n' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication yes' \
      'PermitRootLogin no' \
      'UsePAM yes' \
      'AllowTcpForwarding yes' \
      'ClientAliveInterval 30' \
      > /etc/ssh/sshd_config.d/99-agentsterm-e2e.conf

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]

# ---------------------------------------------------------------------------
# 4. AlmaLinux 9 / RHEL 9 compatible (bash, curl, cronie, zero git)
# ---------------------------------------------------------------------------
FROM almalinux:9 AS vm-almalinux-9

RUN dnf install -y --setopt=install_weak_deps=False \
    cronie \
    openssh-server \
    procps-ng \
    shadow-utils \
    socat \
    && dnf clean all \
    && ! command -v git

RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN mkdir -p /etc/ssh/sshd_config.d \
    && printf '%s\n' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication yes' \
      'PermitRootLogin no' \
      'UsePAM no' \
      'AllowTcpForwarding yes' \
      'ClientAliveInterval 30' \
      > /etc/ssh/sshd_config.d/99-agentsterm-e2e.conf

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]

# ---------------------------------------------------------------------------
# 5. Fedora 41 (bash, curl, NO cron -> detached-only fallback, zero git/python3)
# ---------------------------------------------------------------------------
FROM fedora:41 AS vm-fedora-41

RUN dnf install -y --setopt=install_weak_deps=False \
    busybox \
    openssh-server \
    passwd \
    procps-ng \
    shadow-utils \
    socat \
    && dnf clean all \
    && ! command -v git \
    && ! command -v python3 \
    && ! command -v crontab

RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN mkdir -p /etc/ssh/sshd_config.d \
    && printf '%s\n' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication yes' \
      'PermitRootLogin no' \
      'UsePAM no' \
      'AllowTcpForwarding yes' \
      'ClientAliveInterval 30' \
      > /etc/ssh/sshd_config.d/99-agentsterm-e2e.conf

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]

# ---------------------------------------------------------------------------
# 6. Amazon Linux 2023 / AWS EC2 default (bash, curl, NO cron, zero git)
# ---------------------------------------------------------------------------
FROM amazonlinux:2023 AS vm-amazonlinux-2023

RUN dnf install -y --setopt=install_weak_deps=False \
    openssh-server \
    procps-ng \
    shadow-utils \
    socat \
    && dnf clean all \
    && ! command -v git \
    && ! command -v crontab

RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN mkdir -p /etc/ssh/sshd_config.d \
    && printf '%s\n' \
      'PasswordAuthentication yes' \
      'PubkeyAuthentication yes' \
      'PermitRootLogin no' \
      'UsePAM no' \
      'AllowTcpForwarding yes' \
      'ClientAliveInterval 30' \
      > /etc/ssh/sshd_config.d/99-agentsterm-e2e.conf

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]

# ---------------------------------------------------------------------------
# 7. Alpine Linux 3.21 (pure musl, BusyBox ash, wget-only/NO curl, zero git/python3)
# ---------------------------------------------------------------------------
FROM alpine:3.21 AS vm-alpine-321

RUN apk add --no-cache \
    bash \
    busybox-extras \
    ca-certificates \
    openssh \
    procps-ng \
    socat \
    && ! command -v git \
    && ! command -v python3 \
    && ! command -v curl

RUN adduser -D -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

RUN sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PubkeyAuthentication .*/PubkeyAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config \
    && echo "AllowTcpForwarding yes" >> /etc/ssh/sshd_config \
    && echo "ClientAliveInterval 30" >> /etc/ssh/sshd_config

COPY fake-bin/tailscale /usr/local/bin/tailscale
COPY fake-bin/agy /usr/local/bin/agy
COPY fake-bin/claude /usr/local/bin/claude
COPY fake-bin/codex /usr/local/bin/codex
COPY vm-entrypoint.sh /usr/local/bin/vm-entrypoint.sh

RUN chmod 755 \
    /usr/local/bin/tailscale \
    /usr/local/bin/agy \
    /usr/local/bin/claude \
    /usr/local/bin/codex \
    /usr/local/bin/vm-entrypoint.sh

EXPOSE 22 17591
ENTRYPOINT ["/usr/local/bin/vm-entrypoint.sh"]
