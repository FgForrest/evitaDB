##
## build context in directory: evita_performance_tests
##

FROM ubuntu:jammy

USER root

RUN apt-get update && \
    apt-get install -y openjdk-17-jdk-headless
RUN apt-get install -y wget
RUN apt-get install -y gh
RUN apt-get install -y unzip
RUN apt-get install -y git

WORKDIR evita_performance_tests

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
