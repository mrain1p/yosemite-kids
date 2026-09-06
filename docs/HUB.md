# The hub

An always-on peer for the family config. It runs in Docker on anything that
stays powered — a NAS, a Pi, a spare box — holds the same `config.json` the
phones and TVs hold, and merges with them over the existing LAN routes.

It is entirely optional. What it buys today is that two parents stay in step
without both being home: an edit made on one phone reaches the other through
the hub rather than waiting for a television both happen to be near.

**The TVs use it too, with one honest limit.** A television cannot join a hub
itself — its entire parent settings screen is a QR code, so there is nowhere
to type an address — so the phone introduces them: joining a hub also mints a
token for every TV that phone administers and hands it over. From then on the
TV reconciles with the hub directly, using the same code path a phone does.

The limit: a TV reconciles when Yosemite Kids opens on it and while a kid is
looking at it, **not** while it is asleep or on another app. The sweep lives
in the ViewModel behind a foreground check and there is no background worker.
So the hub means "current the moment a kid opens it, without a parent being
home" — not "always up to date". Making the latter true needs a background
tick, which is in "Next up" in `docs/FORK-NOTES.md`.

Nothing in the app's main source set knows the hub exists. It enrols as an
ordinary `PairedDevice` named `Yosemite Kids hub`, and every sync path that already
worked with a TV works with it unchanged. See `docs/PLAN-hub.md` for why it is
built that way.

## Deploying

CI builds the image on every push that touches `hub/`, `core/` or `crawl/`
(`.github/workflows/hub-image.yml`) and pushes it to GHCR as
`ghcr.io/mrain1p/yosemite-kids-hub:latest`, so the NAS pulls a finished jar
and never compiles anything. The compose file is pull-only.

### One-time: make the package public

The repository is public, but a GHCR package keeps its own visibility, and
this one was first published while the repo was private. Until it is flipped,
an anonymous pull is refused (`denied` / `authentication required`). On
GitHub: your profile → **Packages** → `yosemite-kids-hub` → **Package
settings** → *Danger Zone* → **Change visibility** → Public. The image holds
only the open source above, no token and no family data, so there is nothing
to protect by keeping it private.

The alternative, if you would rather not: the NAS logs in once with a classic
personal access token carrying the single scope `read:packages`:

```
read -rs -p "Token: " T && echo "$T" | sudo docker login ghcr.io -u <username> --password-stdin; unset T
```

`read -rs` prompts without echoing, so the token reaches neither the shell
history nor the process list. Log in as the same user that runs compose:
Docker keeps credentials per user, and a login as yourself leaves `sudo docker
compose pull` with none.

### Running it

Either as a Container Manager project (DSM 7.2): **Project → Create**, paste
`hub/docker-compose.yml`, pick any folder for the project, **Build**, then
**Run**. Or from a shell, with the compose file wherever you keep it:

```
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
```

Updating is the same two commands. The only path that matters is the volume
line: `/volume2/Docker/yosemite-kids/data:/data` holds `config.json`,
`devices.json` and the search index. Keep it, and every enrolled device and
the whole configuration survive a move between projects; lose it, and every
device has to enrol again.

Before the first run as a project, remove the container the old compose
folder started, or the new one fails on the name and the port:

```
docker rm -f yosemite-kids-hub
```

The data on the volume is untouched by that.

### Building on the NAS instead

The fallback when GHCR is unreachable, or for hacking on the hub from the box
itself. The NAS has no `git`; fetch a release's source from GitHub and unpack
it beside `data/`:

```
cd /volume2/Docker/yosemite-kids
curl -L -o src.tar.gz https://github.com/mrain1p/yosemite-kids/archive/refs/tags/v1.0.5.tar.gz
tar -xzf src.tar.gz --strip-components=1 && rm src.tar.gz
sudo docker build -f hub/Dockerfile -t ghcr.io/mrain1p/yosemite-kids-hub:latest .
docker compose -f hub/docker-compose.yml up -d
```

`sudo` on the build is not optional here. The repo-root `.dockerignore`
allow-lists what the Dockerfile copies (guard 13 keeps it so), but DSM's older
builder still stats every top-level entry while scanning the context, and
`data/` belongs to the container's uid, so the scan stops at
`can't stat '.../data'` for an ordinary user. This document used to claim the
ignore file made `sudo` unnecessary; it does not, on this NAS.

**The first build takes 10 to 20 minutes and prints almost nothing.** Gradle
downloads its own distribution and then the Kotlin compiler, quietly. It is
not stuck; `docker stats` will show it burning CPU. Every build after that is
cached and takes seconds.

Then, either way:

```
docker logs yosemite-kids-hub --tail 20
```

A healthy start looks like this:

```
Data volume /data is writable as uid 10001.
Yosemite Kids hub listening on 8765, data in /data
Devices enrolled: 0
Admin token: <24 hex characters>
Nothing paired yet. Approve a device code to pair the first one.
```

Confirm it from another machine on the LAN:

```
curl http://<host>:8765/health          -> ok
curl -i http://<host>:8765/status       -> 401, which is correct
```

`/health` is deliberately the only unauthenticated route. A 401 from `/status`
is the proof that the LAN cannot read a family's configuration without a
token.

## The admin GUI

Open `http://<host>:8765/` in a browser on the same network and sign in with
the admin token from the log. From there you can manage channels and blocked
videos, approve or remove devices, and see what the hub holds.

The status page names every setting that exists on the phone and is not here
yet, so "can I do this on the NAS?" is answerable without guessing. That list
comes from `SettingsSurface` in `:core`, which is the same list the build
checks — a page cannot exist without an entry, and an entry cannot claim to be
built without a page.

Notes on the session:

- It lives in memory. Restarting the hub signs everyone out. That is
  deliberate: a bearer credential never lands on the volume.
- Sign-in is throttled. Eight wrong tokens in fifteen minutes and it refuses
  everything, the correct token included, until the window passes.
- The API key is not editable here and will not be. The hub strips secrets
  before writing and has no keystore, so a key typed here could not survive a
  restart. It stays on the phone.

## The port

The hub publishes 8765. Both the `ports` line and `YOSEMITE_KIDS_PORT` in
`docker-compose.yml` read the same variable, so they cannot drift — publishing
one port while the process listens on another gives a container that is
running, healthy and unreachable, and the health check does not catch it
because it runs inside the container.

To move it, put `YOSEMITE_KIDS_PORT=9000` in a `.env` beside the compose file. Then
use that port when connecting a phone, because the app assumes 8765.

It no longer uses host networking. The hub is a plain server that devices dial
by IP; it never broadcasts or discovers, so host mode bought nothing and cost
the isolation.

## Connecting a phone

On the phone: Settings, then Devices, then the hub section. Enter the address
(`192.168.1.245:8765`) and the admin token from the log. The phone joins, is
marked a parent device, and the hub appears under Kid devices like any other.

To pin the token instead of reading it from the log each time, set
`YOSEMITE_KIDS_ADMIN_TOKEN` in `docker-compose.yml`.

## The search index

Since 1.0.5 the hub builds the search index — every channel's full list of
videos, so search on a TV answers without asking YouTube — instead of the
parent's phone. It lives under `/data/search-index` beside `config.json`, so
the volume you already back up carries it.

How it takes the job over, and gives it back:

- A device that pulls the index from the hub sends `X-Index-Pull: 1` on
  `GET /index-status`; the hub remembers that per device for a day
  (`HubTokens.armed`). Devices from 1.0.5 pull on every sync.
- Every 15 minutes the hub runs the election (`HubMaster`, rules in `:core`
  `MasterElection`). While armed, it claims the slot — from nobody, or from a
  phone — after a probe proves YouTube answers from the NAS. The phone's
  worker sees the token is not its own and stops crawling.
- The holder re-touches the master stamp every 6 hours. A stamp older than a
  day means the slot is vacant, so a hub that is off for a day hands the job
  back to the first parent phone that syncs; when the hub returns and a device
  pulls again, it takes it back.
- A hub nobody has pulled from for a day stops heartbeating on purpose, so a
  fleet that reverted to older builds gets its phone back as builder.

The crawl (`HubCrawl`) is the same 60-page, 4-seconds-apart batch the phone
ran, every 15 minutes, on one thread. Consecutive failed runs back off,
doubling from 15 minutes to 6 hours, so a NAS address YouTube has walled is
not hammered. The Devices page of the admin GUI shows who builds the index,
how far it is, the last crawl and whether any device is pulling.

The container reaches exactly two things on the network: YouTube, through a
client whose host allow-list is armed at startup, and the devices' `/sync-now`.
Guard 7 in `scripts/check.*` fails the build on anything else.

`mem_limit` in the compose file is 384m to leave the crawl room. Measure the
first full crawl with `docker stats yosemite-kids-hub` and put the number here.

## Permissions — read this if the container restarts in a loop

**Symptom.** `docker ps` shows `Restarting`, and the log repeats a
`FileNotFoundException` on `/data/devices.json.tmp` (or `config.json.tmp`)
every few seconds, with no admin token anywhere in it.

**Cause.** `/data` is a bind mount. The image creates and chowns that folder at
build time, and the mount lands on top of all of it — so the folder's real
ownership and mode are the host's.

On a Synology shared folder this bites in a way that looks impossible. The
folder shows as:

```
d---------+ 2 10001 10001 4096 /volume2/Docker/yosemite-kids/data
```

Mode `000`, with a `+` marking an ACL. The owner is correct and the owner is
still denied, because the POSIX bits grant nothing to anyone; root writes only
because root bypasses the check. A `chown` therefore changes nothing at all,
which is what makes the failure so confusing — the ownership already looks
right.

**Fix.** The container repairs this itself now: `hub/docker-entrypoint.sh`
starts as root, tries each candidate identity by actually writing a file, and
only hands over to the JVM once one of them worked. If you are on an older
image, do it by hand:

```
sudo chmod 770 /volume2/Docker/yosemite-kids/data
sudo docker restart yosemite-kids-hub
```

If the mode reverts to `d---------+`, the share's ACL is re-imposing it. Then
either drop the ACL on that folder with `/usr/syno/bin/synoacltool -del`, or
pin `user: "<your uid>:<your gid>"` in `docker-compose.yml` so the container
runs as an account the ACL already permits — `id -u` gives you the number.
Note that pinning a uid the ACL does not name will not help; running as the
folder's owner is not enough when the mode is 000.

As a last resort, `YOSEMITE_KIDS_ALLOW_ROOT=1` runs the hub as root. It works
everywhere. It is not the fix, and the container says so in the log.

## Why the container starts as root

It drops to uid 10001 with `setpriv` before the JVM is exec'd, so nothing the
hub itself does runs privileged. The brief root phase exists only to repair
the volume, which is exactly the thing an unprivileged process cannot do.

`setpriv` rather than `su` or `gosu`: it execs in place, so the JVM stays PID 1
and receives `docker stop`'s SIGTERM directly. A forking `su` would leave the
hub with no signal handling and a ten-second kill on every restart — and a
write in flight then means a truncated `config.json`, which is the file every
device would sync *from*.

A `USER` instruction in the Dockerfile would start the container unprivileged
and make the repair impossible. `scripts/check.ps1` and `scripts/check.sh`
fail the build if one appears.

## Line endings

Every shell script here must have LF endings. A CRLF script has a shebang
ending in a carriage return, which the kernel cannot resolve, and the error is
`not found` for a file that is plainly present. This cost half an hour on
`gradlew` during the first container build. `.gitattributes` pins it, the
Dockerfile strips CR defensively, and the check scripts fail on any tracked
`.sh` that acquires them.
