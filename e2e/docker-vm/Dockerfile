FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    cron \
    curl \
    git \
    openssh-server \
    procps \
    python3 \
    socat \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Create non-root VM user matching typical Linux dev VM setup.
RUN useradd -m -s /bin/bash vmuser \
    && echo "vmuser:agentsterm-e2e-pass" | chpasswd \
    && mkdir -p /run/sshd /home/vmuser/.ssh /home/vmuser/projects \
    && chown -R vmuser:vmuser /home/vmuser \
    && chmod 700 /home/vmuser/.ssh

# Configure OpenSSH for realistic key and password authentication.
RUN sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PubkeyAuthentication .*/PubkeyAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?UsePAM .*/UsePAM yes/' /etc/ssh/sshd_config \
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
