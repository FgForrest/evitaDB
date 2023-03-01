FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

## not supposed to be overriden in container
ENV EVITA_HOME="/evita"
ARG EVITA_JAR_NAME
ENV EVITA_JAR_NAME="$EVITA_JAR_NAME"

USER root

RUN set -ex \
    && mkdir "$EVITA_HOME" "$EVITA_HOME/bin" "$EVITA_HOME/conf" "$EVITA_HOME/data" \
    && : ## end
COPY entrypoint.sh /
COPY "$EVITA_JAR_NAME" "$EVITA_HOME/bin"
COPY "evita-configuration.yaml" "$EVITA_HOME/conf"

## may be to be overriden in container
ENV EVITA_CONFIG_FILE="$EVITA_HOME/conf/evita-configuration.yaml"
ENV EVITA_STORAGE_DIR="$EVITA_HOME/data"
ENV EVITA_LOG_FILE="$EVITA_HOME/logback.xml"
ENV EVITA_JAVA_OPTS=""
ENV EVITA_ARGS=""

WORKDIR "$EVITA_HOME"
ENTRYPOINT [ "/entrypoint.sh" ]
CMD []
