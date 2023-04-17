##
## build context in directory: evita_performance_tests
##

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

USER root

RUN microdnf install -y wget

RUN rpm --import https://rpm.github.com/gpg.key && \
    curl -sL https://cli.github.com/packages/githubcli.repo | tee /etc/yum.repos.d/githubcli.repo && \
    microdnf install -y gh

WORKDIR evita_performance_tests

ENV data_folder="/data"
ENV shared_gist="abc12461f21d1cc66a541417edcb6ba7"

## benchmark
COPY benchmarks.jar /benchmarks.jar
COPY benchmark.sh /benchmark.sh

## create directories
RUN set -ex \
    && mkdir "/data" "/evita-data" \
    && : ## end

## dbg
RUN set -ex && pwd && ls -la .

ENTRYPOINT [ "/benchmark.sh" ]
CMD []
