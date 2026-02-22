# pxf/cbdb-dev:1 — pxf/singlecluster:3 + pre-built Cloudberry DB
FROM pxf/singlecluster:3 AS singlecluster

# ---------------------------------------------------------------------------
# Stage 2: build Cloudberry on the cbdb-build toolchain image
# ---------------------------------------------------------------------------
FROM apache/incubator-cloudberry:cbdb-build-ubuntu22.04-latest

ENV DEBIAN_FRONTEND=noninteractive

# bake in java 8 and 11 into image
RUN sudo apt-get update && \
    sudo apt-get install -y --no-install-recommends \
      openjdk-8-jdk-headless \
      openjdk-11-jdk-headless \
      curl ca-certificates git && \
    sudo rm -rf /var/lib/apt/lists/*

# Env vars that scripts expect
ENV GPHD_ROOT=/home/gpadmin/workspace/singlecluster
ENV GPHOME=/usr/local/cloudberry-db

# Copy GPHD_ROOT (Hadoop/Hive/HBase/ZK/Tez stack) from singlecluster
COPY --from=singlecluster ${GPHD_ROOT} ${GPHD_ROOT}

# Copy Go toolchain from singlecluster
COPY --from=singlecluster /usr/local/go /usr/local/go
ENV PATH="/usr/local/go/bin:${PATH}"

# Fix ownership after COPY (created as root)
RUN sudo chown -R gpadmin:gpadmin /home/gpadmin/workspace

# Clone Cloudberry source (parametrized via build args)
ARG CLOUDBERRY_REPO=https://github.com/apache/cloudberry.git
ARG CLOUDBERRY_BRANCH=main
RUN git clone --depth 1 -b ${CLOUDBERRY_BRANCH} \
      ${CLOUDBERRY_REPO} /home/gpadmin/workspace/cloudberry

# execute random command to bake in gradle
RUN sudo -u gpadmin bash -lc "cd /home/gpadmin/workspace/cloudberry-pxf/server && ./gradlew javaToolchains"

# Copy and run the build script (without demo cluster creation)
COPY script/build_cloudberrry.sh /tmp/build_cloudberrry.sh
ENV SKIP_DEMO_CLUSTER=true
RUN bash /tmp/build_cloudberrry.sh
