FROM index.docker.io/azul/zulu-openjdk-alpine:17-latest

## not supposed to be overriden in container
ARG EVITA_JAR_NAME
ARG VERSION=not_set
ARG RELEASE_DATE=not_set
ENV EVITA_HOME="/evita"
ENV EVITA_JAR_NAME="$EVITA_JAR_NAME"
ENV SYSTEM_API_PORT="5557"

# Labels with dynamic information based on environment variables and current date
LABEL vendor="FG Forrest, a.s." \
      io.evitadb.version="${VERSION}" \
      io.evitadb.release-date="${RELEASE_DATE}"

HEALTHCHECK --start-period=5m --interval=10s --timeout=2s --retries=3 \
    CMD curl -f http://localhost:$SYSTEM_API_PORT/system/liveness || exit 1

USER root

# Install bash and networking utilities
RUN apk add --no-cache bash netcat-openbsd iproute2 iputils mc tcpdump bind-tools

# Create necessary folders
RUN set -ex \
    && mkdir "$EVITA_HOME" "$EVITA_HOME/bin" "$EVITA_HOME/conf" "$EVITA_HOME/data" "$EVITA_HOME/certificates" \
    && : ## end

# Copy files
COPY "entrypoint.sh" "/"
COPY "$EVITA_JAR_NAME" "$EVITA_HOME/bin"
COPY "evita-configuration.yaml" "$EVITA_HOME/conf"

## may be to be overriden in container
ENV EVITA_CONFIG_FILE="$EVITA_HOME/conf/evita-configuration.yaml"
ENV EVITA_STORAGE_DIR="$EVITA_HOME/data"
ENV EVITA_CERTIFICATE_DIR="$EVITA_HOME/certificates"
ENV EVITA_LOG_FILE="$EVITA_HOME/logback.xml"
ENV EVITA_JAVA_OPTS=""
ENV EVITA_ARGS=""

WORKDIR "$EVITA_HOME"
ENTRYPOINT [ "/entrypoint.sh" ]
CMD []
