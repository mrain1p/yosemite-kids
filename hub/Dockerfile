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
RUN useradd --system --uid 10001 pickwick \
    && mkdir -p /data \
    && chown -R pickwick:pickwick /data
USER pickwick

ENV PICKWICK_DATA=/data
ENV PICKWICK_PORT=8765
VOLUME ["/data"]
EXPOSE 8765

# Bash's own /dev/tcp rather than curl or wget: neither is guaranteed present
# in a JRE base image, and a healthcheck that always fails is worse than none —
# it marks a working container unhealthy and, with the wrong restart policy,
# restarts it forever. This needs no package and checks the thing that matters,
# that the port is actually accepting.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PICKWICK_PORT} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q 200"]

ENTRYPOINT ["./bin/hub"]
