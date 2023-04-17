##
## build context in directory: evita_performance_tests
##

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

ARG GH_VERSION
RUN set -ex \
    && wget "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_amd64.deb" \
    && dpkg -i "gh_${GH_VERSION}_linux_amd64.deb" \
    && rm -f "gh_${GH_VERSION}_linux_amd64.deb" \
    && gh --version \
    && :

WORKDIR evita_performance_tests

ENV data_folder="/data"
ENV shared_gist="abc12461f21d1cc66a541417edcb6ba7"

## benchmark
COPY target/benchmarks.jar /
COPY src/automation/benchmark.sh /benchmark.sh

## create directories
RUN set -ex \
    && mkdir "/data" "/evita-data" \
    && : ## end

## dbg
RUN set -ex && pwd && ls -la . target/benchmarks.jar

ENTRYPOINT [ "/benchmark.sh" ]
CMD []
