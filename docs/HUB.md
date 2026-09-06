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
No password set yet. Open this hub in a browser and set one — the token above is what claims it.
Nothing paired yet. Approve a device code to pair the first one.
```

Once a password is set the fourth line becomes `Admin token: not shown, because
a password is set.` — see "The password" below.

Confirm it from another machine on the LAN:

```
curl http://<host>:8765/health          -> ok
curl -i http://<host>:8765/status       -> 401, which is correct
```

`/health` and `/setup` are the only unauthenticated routes, and `/setup`
carries exactly one key — whether a password has been set. A 401 from `/status`
is the proof that the LAN cannot read a family's configuration without a
token.

## The password

**First run.** Open `http://<host>:8765/` in a browser on the same network. The
page offers to claim the hub: paste the admin token from the container log
(`docker logs yosemite-kids-hub`), choose a password, and it signs you in. It
then shows a **recovery token, once**, behind an "I have saved this" step —
save it where you keep passwords, not on the hub. Nothing can show it again.

You can also sign in with the token and set the password later, from **App, hub
& backup**. Until one is set the page carries a banner saying so, because the
token is printed on every restart and anyone who can read the log can
administer the hub.

**What changes once a password exists:**

- The token stops approving devices. It still signs in, still changes the
  password, still mints a new recovery token — so a leaked log line can no
  longer quietly add a device to your family, only take the hub over visibly,
  by changing the password you meet at your next sign-in.
- It stops being printed. The boot line becomes `Admin token: not shown,
  because a password is set.` To see it for one boot, set
  `YOSEMITE_KIDS_PRINT_ADMIN_TOKEN=1` in the compose file and restart.
- The phone's field renames itself. It asks the hub (`GET /setup`) and says
  "Hub password" instead of "Admin token" — one field either way, because the
  hub decides what matched.

**Changing it.** App, hub & backup → Hub password. The current password (or
your recovery token) is required even though you are already signed in: that
session may be a browser on a kitchen counter. Changing it signs out every
other browser and leaves every enrolled device alone — devices hold their own
enrolment tokens and never see this one.

**Four ways back in, if the password is forgotten.** In the order to try them:

1. The **recovery token** you saved. It is exempt from the lockout below, and
   it both signs in and changes the password.
2. `YOSEMITE_KIDS_ADMIN_TOKEN` in `docker-compose.yml`, if you pinned one. It
   always works and overrides the stored token.
3. Set `YOSEMITE_KIDS_PRINT_ADMIN_TOKEN=1` and restart, if no password had been
   set when the current token was minted — a first set rotates it, so the value
   in an old log is dead.
4. Stop the container and delete the `"password"` object from `/data/devices.json`
   by hand. The hub is then unclaimed again and the page offers to claim it.
   Leave the rest of that file alone: it holds every device's enrolment.

There is deliberately **no reset route**. A `/forgot` endpoint on a box whose
stated future is facing the internet is a second front door, and guard 25 fails
the build if one appears.

**The lockout.** Ten wrong secrets and every sign-in is refused — the right one
included — for fifteen minutes, then thirty, then an hour, doubling to six. Any
success clears it. It is counted globally rather than per address, because on a
LAN an attacker picks their own source address. `/approve` shares that counter,
so a phone's "Connect my TVs" with a wrong password stops at the first refusal
rather than spending an attempt per television.

**How it is stored.** PBKDF2-HMAC-SHA256, 210 000 iterations, a 16-byte salt,
under `password` in `devices.json`. The plaintext is never written. That is not
because the file is a secure place — anyone who can read it already holds every
device token and the recovery token beside it, and the hub is over — but
because families reuse a password, and this volume gets backed up to cloud
drives. Measure the first verify on your own NAS: on a low-power box a sign-in
should still be well under a second.

## The admin GUI

Open `http://<host>:8765/` in a browser on the same network and sign in with
your hub password — or, before you have set one, the admin token from the log.
From there you can manage channels and blocked videos, rule on the videos the
AI is holding back, approve or remove devices, give a kid bonus minutes or turn
watching off until midnight, set or change the password, and see what the hub
holds.

It opens on a home page rather than a row of tabs: the kids at the top with
what each one's rules currently say, two status tiles, then the settings
grouped, each row's second line stating what that page says right now. A kid
and a device are each a page of their own — `#/kids/<id>`, `#/devices/<ref>` —
and the browser's Back button is what comes out of them, because when this is
installed on a phone Back is the only navigation there is.

The **change feed** at the bottom of the home page is the same feed the phone
shows under Recent changes, in the same sentences. Nothing on the hub computes
it: every stamped edit and every merge writes a line, the lines ride inside the
config, and the last thirty arrive here with the next sync. So "why did the TV
change?" is answerable on the box in the cupboard as well as on a phone that
happens to be in the house.

Bonus minutes and the pause are worth one sentence about *when* they land,
because the hub is not a phone. A phone calls each device it can see, so a
television that is awake stops or gains time as the parent's thumb comes off
the button. The hub calls nothing: it holds no credential on any device by
design, so it writes the change into the family config and tells the devices
their copy has moved. Everything awake with the app open takes it in seconds;
everything else takes it the next time it opens. The card on the page says
this too, in those words — a parent standing in the doorway should not have to
come here to find out whether the television has heard yet.

The status page names every setting that exists on the phone and is not here
yet, so "can I do this on the NAS?" is answerable without guessing. That list
comes from `SettingsSurface` in `:core`, which is the same list the build
checks — a page cannot exist without an entry, and an entry cannot claim to be
built without a page.

Most of what you see is drawn from that manifest rather than written out here.
`SettingsSurface` declares each control once — its words, how it is drawn, its
range, and the config key it writes — `GET /api/state` ships the ones this face
is expected to have, and `renderControl` in `index.html` builds them. So a new
switch on the phone appears on the hub from one declaration, and the two faces
cannot call the same rule by two different names. A handful of controls are too
particular for that (the channel list, the device rows, the blocked-times
editor); those are hand-written and marked `data-control="<id>"`, which is how
guard 26 tells a control that was built from one that was only claimed.

### Waiting for your OK

*Content screening* carries the same review queue the phone has: the videos the
AI held back, the ones it blocked, and Allow or Block for the family or for one
child at a time. A ruling made here is an ordinary config edit — it reaches
every device on its next sync, the same way a channel or a bedtime does.

The hub can show this because it holds verdicts now. Every phone pushes what it
has screened to every peer it is paired with on every sweep, the hub included,
and a verdict entry carries the video's title, its channel, a thumbnail and the
AI's own sentence about why it stopped it. That is the whole card, so the hub
needs no copy of the family's feed to draw one. It is also why the queue is
empty on a hub nobody has synced with yet, and why it fills up on its own once
a phone has.

One thing the phone shows and this cannot: "12 still being screened". That
count is measured against a device's cached feed, and the hub has none.

**This page makes third-party requests, and it is the only page here that
does.** The thumbnails are loaded by *your browser*, directly from YouTube's
image CDN, using URLs a device recorded with the verdict. The hub is not in
that fetch — it asks YouTube for nothing on this path, and the container's own
outbound allow-list is unchanged. The requests carry no referrer and no cookie
this page sets, so YouTube learns that some browser asked for some thumbnails
and nothing about your family. If the NAS has no outbound access, or you would
rather your browser did not talk to YouTube at all, the page still works — the
thumbnails simply do not appear, and every ruling is still a title, a channel
and a reason.

### The AI key

The key for the AI that screens videos can live on this box. Set it on
*Content screening*, and every parent's phone that joins this hub is given it
on its next sync — which is the point: screening works for the whole household
without each parent finding the key and typing it again.

**It is stored in plain text**, in `/data/secrets.json`, and there is no honest
way around that. A phone keeps its copy in a Keystore-backed store that the
hardware itself unlocks; a NAS has no such thing, and this container has no
secret of its own to derive one from — a key encrypted with something sitting
on the same volume protects against nothing. So the file is owner-only where
the filesystem allows it (a Synology bind mount often does not, see
*Permissions* below) and that is the whole of it. Anything running as root on
the NAS can read it, so can anything else sharing the bind mount, and so can
whoever holds a backup of that folder.

What follows from that, practically:

- **Use a separate key for this, with a spending cap.** Providers let you mint
  more than one. A key that can only ever spend a few pounds a month is a very
  different thing to lose than the one your other projects use.
- **"Someone got into the NAS" means "rotate the key".** Not "check whether
  they found it" — assume they did, mint a new one, and paste it into
  *Replace the key*. That is one action and it is over.
- **The page never shows it back.** Once set, all it will tell you is the last
  four characters, which is enough to answer "is this the one I pasted?" and
  nothing else. There is no way to read the key out of the GUI, and that is
  deliberate: a field that rendered it would put the value into a browser, its
  autofill, and every screenshot of the page.

Two things it is **not**. It is not in `config.json`, so it is not in the five
snapshots under `versions/`, not in `GET /api/state`, and not in the backup you
download — `HubStoreTest` asserts each of those at every depth. And it is not
given to kid devices: `GET /config` puts it back only for an enrolment the
approver recorded as a parent's phone. A television gets its key from a
parent's phone, as it always has.

Holding the key does not let the hub *call* the AI, and nothing here changed
that. The screener runs on the devices; the hub still reaches exactly two
things — YouTube through `:crawl`'s allow-listed client, and the devices'
`/sync-now` — and guard 7 is untouched by this feature. Whether the hub should
ever screen is a separate decision with its own record.

Rotating is also the one place the merge could have quietly beaten you. The key
is resolved between peers by the `ai` unit's stamp, so setting one here moves
that stamp deliberately: without it, a rotation typed on the NAS and the old
key still held by a television that slept through it would be a tie, broken by
string order, and about half of all rotations would lose to the key they
replaced. Screening would keep working the whole time and the bill would be the
only symptom.

### Backups

*App, hub & backup* has two different things with similar names, and the
difference matters on the day you need one.

**Version history** is the hub's own five-slot ring, kept automatically each
time the settings change. It is for undoing something you just did.

**Download a backup** is one file you keep somewhere that is not this NAS. It
is the same envelope the phone's own export writes, so the file opens on a
phone — which on the day the NAS is gone is the only thing left to open it
with. It carries no API key: the hub strips secrets on every write, so what is
served is what is on disk, and a test asserts that at every depth of the file.

Restoring either one is a **deliberate edit, never a file copy**, and the
distinction is not cosmetic. A restored document carries the stamps it had when
it was taken, so writing those bytes would hand every peer that has edited
since a newer stamp than yours: the restore would quietly undo itself on the
next sync, and the tombstones this hub had learned would go with it — meaning
every channel anyone had removed would come back. So a restore is applied as a
fresh change on top of what is here now. It outranks every device, its
deletions propagate, and a co-parent's bookkeeping survives it.

Notes on the session:

- It lives in memory. Restarting the hub signs everyone out. That is
  deliberate: a bearer credential never lands on the volume.
- Sign-in is throttled, and the lockout escalates. See "The password" above.
- The API key **is** editable here now, and what that costs is written out
  under "The AI key" below. Read it before you paste one in.

### Installing it on a phone

The admin GUI is a progressive web app: a manifest, an icon and a service
worker that caches the page shell — and only the shell. Nothing under
`/api` is ever cached, because a browser's cache outlives the session and
the sign-out, and everything there is the family's configuration.

**On iPhone or iPad**, over plain `http://<nas>:8765`, Safari's *Share →
Add to Home Screen* already gives an icon that opens in its own window with
no address bar.

**On Android**, Chrome offers to install an app only from a secure origin.
Over plain http you get an ordinary bookmark, and the service worker does not
register at all. To get the real thing, front the hub with the reverse proxy
already on this NAS: a host such as `kids.<your domain>` with a certificate,
forwarding to `192.168.1.245:8765`. The GUI's same-origin check compares the
`Origin` header's host with `Host`, both of which the proxy passes through,
so nothing in the hub needs to change. Then open that address and use
*Install app*.

Either way the page behaves identically; installing buys the home-screen
icon, the app window, and a shell that still loads and says "can't reach the
hub" when the container is down instead of a browser error page.

The icons are generated, not drawn: `node scripts/make-hub-icons.js
hub/src/main/resources/web` rewrites them, deterministically, so re-running
it without an edit produces no diff.

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
(`192.168.1.245:8765`) and the hub's secret — the field says which one it wants,
because the phone asks the hub (`GET /setup`) as soon as the address is typed:
"Hub password" once one is set, "Admin token" until then. It is one field
either way; the hub checks the password and the recovery token against the same
header and decides which matched. The phone keeps neither — what it stores is
the per-device enrolment token the hub hands back.

The phone will not connect if the hub is refusing sign-ins: it reports the wait
rather than the address being wrong, and it stops at the first refused secret
instead of spending an attempt per television.

To pin the token instead of reading it from the log each time, set
`YOSEMITE_KIDS_ADMIN_TOKEN` in `docker-compose.yml`. That value always works,
password or no password, which makes it the way back in if the password is
forgotten.

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
