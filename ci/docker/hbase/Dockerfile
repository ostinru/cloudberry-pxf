# pxf/hbase:1 — HBase 2.3.7 standalone (embedded ZK, local FS)
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV HBASE_VERSION=2.3.7
ENV HBASE_SHA512="1032521025660daa70260cdc931f52a26c87596be444451fe1fa88b526ede55e9d6b4220e91ff6f7422bec11f30d64fa6745e95a9c36971fdb1a264a2c745693"
ENV HBASE_HOME=/opt/hbase
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH="${HBASE_HOME}/bin:${JAVA_HOME}/bin:${PATH}"

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      openjdk-11-jdk-headless curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p $HBASE_ROOT && \
    curl -fSL "$HBASE_URL" -o hbase.tar.gz && \
    echo "$HBASE_SHA512 hbase.tar.gz" | sha512sum -c && \
    tar xvf hbase.tar.gz -C $HBASE_ROOT --strip-components 1 --exclude="docs/*" --exclude="lib/*-tests.jar" --exclude="lib/shaded-clients" && \
    rm hbase.tar.gz

COPY hbase-site.xml ${HBASE_HOME}/conf/hbase-site.xml

RUN echo 'export HBASE_MANAGES_ZK=true' >> ${HBASE_HOME}/conf/hbase-env.sh && \
    echo "export JAVA_HOME=${JAVA_HOME}" >> ${HBASE_HOME}/conf/hbase-env.sh

# ZK client, HBase Master RPC, RegionServer RPC
EXPOSE 2181 16000 16020

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

CMD ["/entrypoint.sh"]
