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
RUN chmod +x gradlew && ./gradlew --no-daemon :hub:dependencies --quiet || true

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

# The unauthenticated route, so an orchestrator can tell "starting" from
# "wedged" without holding a device token.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD ["/bin/sh", "-c", "wget -qO- http://127.0.0.1:${PICKWICK_PORT}/health || exit 1"]

ENTRYPOINT ["./bin/hub"]
