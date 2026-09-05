#!/bin/sh
set -e

# Make /data genuinely writable before the hub touches it, or explain why not.
#
# A bind-mounted /data undoes whatever the image did at build time: the host
# folder arrives with the host's ownership, and on a NAS it is usually governed
# by an ACL as well, which a chown cannot override. Get this wrong and the hub
# dies on its first write, restart:unless-stopped brings it back, and the log
# is an endless Java stack trace with no admin token in it — the one thing
# anyone actually needs from that log.
#
# The first version of this script chowned and exec'd, assuming a successful
# chown meant a writable directory. On a Synology share it does not. So this
# version proves it instead: every candidate identity is tested by actually
# writing a file, and the JVM is only reached once one of them worked.

DATA="${YOSEMITE_KIDS_DATA:-/data}"
DEFAULT_UID=10001
DEFAULT_GID=10001
PROBE="$DATA/.yosemite-kids-write-test"

# Can this identity create a file in the volume? Nothing else is a real answer.
can_write() {
    setpriv --reuid="$1" --regid="$2" --clear-groups \
        sh -c "touch '$PROBE' 2>/dev/null && rm -f '$PROBE'"
}

diagnose() {
    echo "=== the hub cannot write to $DATA ===" >&2
    echo "volume:  $(ls -ldn "$DATA" 2>&1)" >&2
    echo "mode:    $(stat -c %a "$DATA" 2>&1) — 0 here means the ACL, not the bits, decides" >&2
    echo "tried:   uid ${1:-none} — and uid $DEFAULT_UID after taking ownership" >&2
    echo >&2
    echo "The folder's POSIX ownership is not the whole story on a NAS: a" >&2
    echo "Synology shared folder carries an ACL that overrides it, and chown" >&2
    echo "cannot clear that from inside a container. On the host, either" >&2
    echo "drop the ACL on the mounted folder:" >&2
    echo "    sudo synoacltool -del <the host folder>" >&2
    echo "or give it to a real account and let this container adopt that uid:" >&2
    echo "    sudo chown -R <you>:users <the host folder>" >&2
    echo >&2
    echo "Set YOSEMITE_KIDS_ALLOW_ROOT=1 in docker-compose.yml to run as root" >&2
    echo "instead. That works everywhere and is the last resort, not the fix." >&2
}

# Someone pinned `user:` in compose. A legitimate choice, but it means nothing
# here can repair anything — so check, report, and stop rather than failing
# later inside the JVM.
if [ "$(id -u)" != "0" ]; then
    mkdir -p "$DATA" 2>/dev/null || true
    if touch "$PROBE" 2>/dev/null; then
        rm -f "$PROBE"
        exec "$@"
    fi
    echo "The hub is running as uid $(id -u), which cannot write to $DATA." >&2
    echo "Remove the 'user:' line from docker-compose.yml to let the container" >&2
    echo "sort out ownership itself, or chown the host folder to uid $(id -u)." >&2
    exit 1
fi

mkdir -p "$DATA"

owner_uid=$(stat -c %u "$DATA")
owner_gid=$(stat -c %g "$DATA")
target_uid=""

# 1. Whoever already owns the volume. On a NAS this is usually a real account,
#    and the ACL is written in terms of it — so it is the identity most likely
#    to be permitted, and adopting it needs no chown at all.
if [ "$owner_uid" != "0" ] && can_write "$owner_uid" "$owner_gid"; then
    target_uid="$owner_uid"
    target_gid="$owner_gid"

# 2. The image's own user, if the volume already allows it.
elif can_write "$DEFAULT_UID" "$DEFAULT_GID"; then
    target_uid="$DEFAULT_UID"
    target_gid="$DEFAULT_GID"

# 3. Only now is a chown worth attempting — and it is still not believed until
#    a write succeeds afterwards.
else
    echo "Taking ownership of $DATA (uid $DEFAULT_UID)."
    chown -R "$DEFAULT_UID:$DEFAULT_GID" "$DATA" 2>/dev/null || true

    # And the mode, separately. A folder created through a NAS share can
    # arrive as mode 000 with an ACL carrying the real permissions — its own
    # owner is then denied, so a chown on its own changes nothing. Root can
    # still fix the bits, which is most of why this script starts as root.
    if ! can_write "$DEFAULT_UID" "$DEFAULT_GID"; then
        chmod u+rwX,g+rwX "$DATA" 2>/dev/null || true
    fi

    if can_write "$DEFAULT_UID" "$DEFAULT_GID"; then
        target_uid="$DEFAULT_UID"
        target_gid="$DEFAULT_GID"
    fi
fi

if [ -z "$target_uid" ]; then
    if [ "${YOSEMITE_KIDS_ALLOW_ROOT:-}" = "1" ]; then
        echo "WARNING: running the hub as root. $DATA denied every unprivileged" >&2
        echo "identity, and YOSEMITE_KIDS_ALLOW_ROOT=1 is set. Fix the host folder's" >&2
        echo "permissions and remove that setting when you can." >&2
        exec "$@"
    fi
    diagnose "$owner_uid"
    exit 1
fi

echo "Data volume $DATA is writable as uid $target_uid."
# setpriv rather than su or gosu: it execs in place, so the JVM stays PID 1 and
# receives docker stop's SIGTERM directly. A forking su would leave the hub
# with no signal handling and a ten-second kill on every restart.
exec setpriv --reuid="$target_uid" --regid="$target_gid" --clear-groups "$@"
