##
## build context in directory: evita_performance_tests
##

FROM ubuntu:jammy

ARG DEBIAN_FRONTEND=noninteractive
ENV TZ=Europe/Prague

USER root

RUN apt-get update && apt-get install -y \ 
    openjdk-17-jdk-headless \
    curl \
    wget \
    gh \
    unzip \
    git \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

RUN ln -fs /usr/share/zoneinfo/$TZ /etc/localtime && \
    dpkg-reconfigure -f noninteractive tzdata

WORKDIR /evita_performance_tests

ENV data_folder="/data"
ENV shared_gist="abc12461f21d1cc66a541417edcb6ba7"

## benchmark
COPY benchmarks.jar /evita_performance_tests/benchmarks.jar
COPY benchmark.sh /benchmark.sh

## create directories
RUN set -ex \
    && mkdir "/data" "/evita-data" \
    && : ## end

## dbg
RUN set -ex && pwd && ls -la .

ENTRYPOINT [ "/benchmark.sh" ]
CMD []
