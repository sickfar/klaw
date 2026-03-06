#!/bin/sh
set -e

# Detect docker.sock GID and ensure klaw user can access it
if [ -S /var/run/docker.sock ]; then
    SOCK_GID=$(stat -c '%g' /var/run/docker.sock)
    if ! id -G klaw | tr ' ' '\n' | grep -q "^${SOCK_GID}$"; then
        # Create or reuse a group with the socket's GID, then add klaw to it
        EXISTING_GROUP=$(getent group "$SOCK_GID" | cut -d: -f1 || true)
        if [ -z "$EXISTING_GROUP" ]; then
            groupadd -g "$SOCK_GID" dockersock
            EXISTING_GROUP=dockersock
        fi
        usermod -aG "$EXISTING_GROUP" klaw
    fi
fi

exec gosu klaw "$@"
