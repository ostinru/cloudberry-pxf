FROM apache/incubator-cloudberry:cbdb-build-ubuntu22.04-latest

ENV DEBIAN_FRONTEND noninteractive

RUN sudo apt-get update &&  \
    sudo apt-get install -y --no-install-recommends \
      curl ca-certificates \
      openjdk-8-jdk-headless \
      openjdk-11-jdk-headless

# TODO: update hive to support java 11+
ENV    HADOOP_VERSION=3.1.2
ENV      HIVE_VERSION=3.1.3
ENV       TEZ_VERSION=0.9.2

# checksums from archive.apache.org
ENV    HADOOP_SHA512="0e0ee817c89b3c4eb761eca7f16640742a83b0e99b6fda26c1bee2baabedad93aab86e252bf5f1e2381c6d464bc4003d10c7cc0f61b2062f4c59732ca24d1bd9"
ENV      HIVE_SHA256="0c9b6a6359a7341b6029cc9347435ee7b379f93846f779d710b13f795b54bb16"
ENV      TEZ_SHA512="a2d94bd9fa778d42a8bac9d9da8e263e469ddfef93968b06434716554995f490231de5607541ac236e770aa0158b64250c38bc1cd57dbfa629fea705f2ffa2f5"

# faster mirror:
ENV APACHE_MIRROR="repo.huaweicloud.com/apache"
#ENV APACHE_MIRROR="archive.apache.org/dist/"
#ENV APACHE_MIRROR="mirror.yandex.ru/mirrors/apache/"

ENV    HADOOP_URL="https://$APACHE_MIRROR/hadoop/common/hadoop-$HADOOP_VERSION/hadoop-$HADOOP_VERSION.tar.gz"
ENV      HIVE_URL="https://$APACHE_MIRROR/hive/hive-$HIVE_VERSION/apache-hive-$HIVE_VERSION-bin.tar.gz"
ENV       TEZ_URL="https://$APACHE_MIRROR/tez/$TEZ_VERSION/apache-tez-$TEZ_VERSION-bin.tar.gz"

ENV GPHD_ROOT=/home/gpadmin/workspace/singlecluster
ENV HADOOP_ROOT=$GPHD_ROOT/hadoop
ENV HIVE_ROOT=$GPHD_ROOT/hive
ENV TEZ_ROOT=$GPHD_ROOT/tez

RUN mkdir -p $HADOOP_ROOT && \
    curl -fSL "$HADOOP_URL" -o hadoop.tar.gz && \
    echo "$HADOOP_SHA512 hadoop.tar.gz" | sha512sum -c && \
    tar xvf hadoop.tar.gz -C $HADOOP_ROOT --strip-components 1  --exclude="share/doc/*" --exclude="*-sources.jar" && \
    rm hadoop.tar.gz && \
    curl -fSL "https://repo1.maven.org/maven2/javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0.jar" \
      -o $HADOOP_ROOT/share/hadoop/common/lib/javax.activation-api-1.2.0.jar

RUN mkdir -p $HIVE_ROOT && \
    curl -fSL $HIVE_URL -o hive.tar.gz && \
    echo "$HIVE_SHA256 hive.tar.gz" | sha256sum -c && \
    tar xvf hive.tar.gz -C $HIVE_ROOT --strip-components 1 && \
    rm hive.tar.gz

RUN mkdir -p $TEZ_ROOT && \
    curl -fSL "$TEZ_URL" -o tez.tar.gz && \
    echo "$TEZ_SHA512 tez.tar.gz" | sha512sum -c && \
    tar xvf tez.tar.gz -C $TEZ_ROOT --strip-components 1 && \
    rm tez.tar.gz

# Install Go (required by PXF). Pick archive based on architecture (amd64/arm64).
ARG TARGETARCH
RUN set -e; \
    arch="${TARGETARCH:-$(uname -m)}"; \
    case "$arch" in \
      amd64|x86_64) go_arch="amd64" ;; \
      arm64|aarch64) go_arch="arm64" ;; \
      *) echo "Unsupported architecture: ${arch}"; exit 1 ;; \
    esac; \
    mkdir -p /tmp/pxf_src/ && cd /tmp && \
    wget -O go.tgz -q "https://go.dev/dl/go1.23.3.linux-${go_arch}.tar.gz" && \
    sudo rm -rf /usr/local/go && sudo tar -C /usr/local -xzf go.tgz && rm go.tgz

COPY ./templates $GPHD_ROOT
COPY ./conf $GPHD_ROOT/conf
COPY ./bin $GPHD_ROOT/bin