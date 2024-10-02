FROM index.docker.io/azul/zulu-openjdk:17-latest

## input arguments for the build
ARG EVITA_JAR_NAME
ARG VERSION=not_set
ARG RELEASE_DATE=not_set

## 1. Environment variables, NOT supposed to be overriden in container ##
ENV EVITA_HOME="/evita"
ENV EVITA_JAR_NAME="$EVITA_JAR_NAME"
ENV SYSTEM_API_PORT="5555"

## 2. Environment variables, MAY BE overriden in container ##

## using volumes:

## folder with configuration files, all of them are applied one on top of another in alphabetical order
ENV EVITA_BIN_DIR="$EVITA_HOME/bin/"
ENV EVITA_CONFIG_DIR="$EVITA_HOME/conf/"
ENV EVITA_STORAGE_DIR="$EVITA_HOME/data/"
ENV EVITA_EXPORT_DIR="$EVITA_HOME/export/"
ENV EVITA_CERTIFICATE_DIR="$EVITA_HOME/certificates/"
ENV EVITA_LOG_FILE="$EVITA_HOME/logback.xml"

## using environment variables
ENV EVITA_JAVA_OPTS=""
ENV EVITA_ARGS=""

## 3. Metadata definition part ##

# Labels with dynamic information based on environment variables and current date
LABEL vendor="FG Forrest, a.s." \
      io.evitadb.version="${VERSION}" \
      io.evitadb.release-date="${RELEASE_DATE}"

HEALTHCHECK --start-period=5m --interval=10s --timeout=2s --retries=3 \
    CMD curl -f http://localhost:$SYSTEM_API_PORT/system/liveness || exit 1

## 4. Image build part

USER root

# Install bash and networking utilities
RUN apt update && apt install -y netcat-openbsd iproute2 iputils-ping mc tcpdump dnsutils curl

# Create necessary folders
RUN set -ex \
    && mkdir "$EVITA_HOME" "$EVITA_BIN_DIR" "$EVITA_CONFIG_DIR" "$EVITA_STORAGE_DIR" "$EVITA_EXPORT_DIR" "$EVITA_CERTIFICATE_DIR" \
    && : ## end

# Copy files
COPY "entrypoint.sh" "/"
COPY "$EVITA_JAR_NAME" "$EVITA_BIN_DIR"

## 5. Startup part ##

WORKDIR "$EVITA_HOME"
ENTRYPOINT [ "/entrypoint.sh" ]
CMD []
