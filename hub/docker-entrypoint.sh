#!/bin/sh
set -e

# Make /data writable before the hub touches it.
#
# The image creates /data and chowns it to the unprivileged user at build time,
# and that is completely undone the moment a bind mount lands on top: the host
# folder arrives owned by whoever created it — root, on every NAS I have seen —
# and the container's uid cannot write. The hub then dies on its first write,
# restart:unless-stopped brings it straight back, and the log is an endless
# stack trace with no admin token in it. The cause is invisible from inside the
# container and the fix (chown to a numeric uid that does not exist on the
# host) is not something a parent should have to work out.
#
# So the container fixes it. It starts as root, takes ownership of the volume
# only, drops to the unprivileged user, and execs. Nothing the hub itself does
# ever runs with privilege.

DATA="${PICKWICK_DATA:-/data}"
UID_PICKWICK=10001

if [ "$(id -u)" = "0" ]; then
    mkdir -p "$DATA"
    # Only walk the tree when it is actually wrong. On a spinning-disk NAS a
    # recursive chown on every restart is a cost for nothing.
    if [ "$(stat -c %u "$DATA")" != "$UID_PICKWICK" ]; then
        echo "Taking ownership of $DATA (uid $UID_PICKWICK)."
        chown -R "$UID_PICKWICK:$UID_PICKWICK" "$DATA"
    fi
    # setpriv rather than su or gosu: it execs in place, so the JVM stays PID 1
    # and receives docker stop's SIGTERM directly. A forking su would leave the
    # hub with no signal handling and a ten-second kill on every restart.
    exec setpriv --reuid="$UID_PICKWICK" --regid="$UID_PICKWICK" --clear-groups "$@"
fi

# Someone pinned `user:` in compose. That is a legitimate choice, but it means
# we cannot fix ownership — so check now and say exactly what to do, rather
# than failing later inside a stack trace.
if ! ( mkdir -p "$DATA" && touch "$DATA/.pickwick-write-test" ) 2>/dev/null; then
    echo "The hub cannot write to $DATA." >&2
    echo "It is running as uid $(id -u), which the mounted folder does not allow." >&2
    echo "Either remove the 'user:' line from docker-compose.yml, so the container" >&2
    echo "can take ownership itself, or on the host run:" >&2
    echo "    chown -R $(id -u) <the folder mounted at $DATA>" >&2
    exit 1
fi
rm -f "$DATA/.pickwick-write-test"

exec "$@"
