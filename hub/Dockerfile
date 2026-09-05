# Two stages so the runtime image carries a JRE and one jar, not a JDK and a
# Gradle cache. A NAS pulls this over a home connection; size is a courtesy.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# The wrapper and the build files first, so the dependency download layer is
# cached and only re-runs when the build definition actually changes.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core/build.gradle.kts ./core/
COPY hub/build.gradle.kts ./hub/
# Strip CR before anything tries to run it. The source may well have arrived
# from a Windows checkout — over a share, in a zip, out of a git clone with
# autocrlf on — and a shell script with CRLF endings has a shebang of
# "#!/bin/sh\r", which the kernel cannot resolve. The error it produces is
# "./gradlew: not found" for a file that is visibly right there, which is a
# genuinely misleading half hour if you have not seen it before.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# Silent by design, and slow: this pulls the Kotlin compiler and stdlib, which
# on a home connection is many minutes with nothing printed. The warning about
# that lives in docker-compose.yml, where someone reads it before starting
# rather than while staring at a stalled terminal.
RUN ./gradlew --no-daemon :hub:dependencies --quiet || true

# Only the two modules the hub needs. :app is Android and would drag the whole
# SDK into a build that has no use for it.
COPY core/src ./core/src
COPY hub/src ./hub/src
RUN ./gradlew --no-daemon :hub:installDist --quiet

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/hub/build/install/hub ./

# Not root. The volume is a family's configuration, and a container that never
# needs to write outside /data has no business running privileged.
#
# There is deliberately no USER line, though. A bind-mounted /data arrives
# owned by the host and shadows the chown below, leaving the hub unable to
# write; the entrypoint starts as root purely to repair that, then drops to
# this user before the JVM is exec'd. See docker-entrypoint.sh.
RUN useradd --system --uid 10001 yosemitekids \
    && mkdir -p /data \
    && chown -R yosemitekids:yosemitekids /data

# The entrypoint drops privileges with setpriv. If a future base image stops
# shipping util-linux, fail here — at build time, visibly — rather than at
# every container start, in a restart loop, with the real cause buried in a
# shell error nobody reads.
RUN command -v setpriv >/dev/null || (echo 'setpriv missing from base image' >&2; exit 1)

# Same CR strip as gradlew, for the same reason: this file is routinely copied
# from a Windows checkout, and a shebang of "#!/bin/sh\r" fails with "not
# found" for a script that is plainly right there.
COPY hub/docker-entrypoint.sh /usr/local/bin/yosemite-kids-entrypoint
RUN sed -i 's/\r$//' /usr/local/bin/yosemite-kids-entrypoint \
    && chmod +x /usr/local/bin/yosemite-kids-entrypoint

ENV YOSEMITE_KIDS_DATA=/data
ENV YOSEMITE_KIDS_PORT=8765
VOLUME ["/data"]
EXPOSE 8765

# Bash's own /dev/tcp rather than curl or wget: neither is guaranteed present
# in a JRE base image, and a healthcheck that always fails is worse than none —
# it marks a working container unhealthy and, with the wrong restart policy,
# restarts it forever. This needs no package and checks the thing that matters,
# that the port is actually accepting.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${YOSEMITE_KIDS_PORT} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q 200"]

ENTRYPOINT ["/usr/local/bin/yosemite-kids-entrypoint", "./bin/hub"]
