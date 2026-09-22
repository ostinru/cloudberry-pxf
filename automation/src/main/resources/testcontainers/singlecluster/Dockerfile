# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM eclipse-temurin:8-jdk-jammy@sha256:4e6409efc7e022f46b4969fb489c12fe2869c49f176829b948874a7c8ebb4b84

# Versions and verified distributions intentionally match ci/singlecluster.
ENV HADOOP_VERSION=3.1.2 \
    HIVE_VERSION=3.1.3 \
    TEZ_VERSION=0.9.2 \
    HADOOP_SHA512=0e0ee817c89b3c4eb761eca7f16640742a83b0e99b6fda26c1bee2baabedad93aab86e252bf5f1e2381c6d464bc4003d10c7cc0f61b2062f4c59732ca24d1bd9 \
    HIVE_SHA256=0c9b6a6359a7341b6029cc9347435ee7b379f93846f779d710b13f795b54bb16 \
    TEZ_SHA512=a2d94bd9fa778d42a8bac9d9da8e263e469ddfef93968b06434716554995f490231de5607541ac236e770aa0158b64250c38bc1cd57dbfa629fea705f2ffa2f5 \
    APACHE_MIRROR=repo.huaweicloud.com/apache \
    GPHD_ROOT=/home/gpadmin/workspace/singlecluster \
    JAVA_HOME=/opt/java/openjdk
ENV HADOOP_ROOT=${GPHD_ROOT}/hadoop \
    HIVE_ROOT=${GPHD_ROOT}/hive \
    TEZ_ROOT=${GPHD_ROOT}/tez
ENV PATH=${GPHD_ROOT}/bin:${HADOOP_ROOT}/bin:${HIVE_ROOT}/bin:${JAVA_HOME}/bin:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl procps \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd gpadmin \
    && useradd --create-home --gid gpadmin gpadmin \
    && useradd --create-home testuser \
    && useradd --create-home foobar \
    && mkdir -p "${HADOOP_ROOT}" "${HIVE_ROOT}" "${TEZ_ROOT}" \
    && curl -fSL --retry 3 \
       "https://${APACHE_MIRROR}/hadoop/common/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz" \
       -o /tmp/hadoop.tar.gz \
    && echo "${HADOOP_SHA512}  /tmp/hadoop.tar.gz" | sha512sum -c \
    && tar xzf /tmp/hadoop.tar.gz -C "${HADOOP_ROOT}" --strip-components 1 \
       --exclude='share/doc/*' --exclude='*-sources.jar' \
    && curl -fSL --retry 3 \
       "https://${APACHE_MIRROR}/hive/hive-${HIVE_VERSION}/apache-hive-${HIVE_VERSION}-bin.tar.gz" \
       -o /tmp/hive.tar.gz \
    && echo "${HIVE_SHA256}  /tmp/hive.tar.gz" | sha256sum -c \
    && tar xzf /tmp/hive.tar.gz -C "${HIVE_ROOT}" --strip-components 1 \
    && curl -fSL --retry 3 \
       "https://${APACHE_MIRROR}/tez/${TEZ_VERSION}/apache-tez-${TEZ_VERSION}-bin.tar.gz" \
       -o /tmp/tez.tar.gz \
    && echo "${TEZ_SHA512}  /tmp/tez.tar.gz" | sha512sum -c \
    && tar xzf /tmp/tez.tar.gz -C "${TEZ_ROOT}" --strip-components 1 \
    && curl -fSL --retry 3 \
       https://repo1.maven.org/maven2/javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0.jar \
       -o "${HADOOP_ROOT}/share/hadoop/common/lib/javax.activation-api-1.2.0.jar" \
    && rm /tmp/hadoop.tar.gz /tmp/hive.tar.gz /tmp/tez.tar.gz \
    && chown -R gpadmin:gpadmin /home/gpadmin

# These are selected copies of ci/singlecluster. HBase, Ranger, PXF and Go
# resources are deliberately absent from this image.
COPY --chown=gpadmin:gpadmin templates ${GPHD_ROOT}
COPY --chown=gpadmin:gpadmin conf ${GPHD_ROOT}/conf
COPY --chown=gpadmin:gpadmin bin ${GPHD_ROOT}/bin
COPY --chown=gpadmin:gpadmin httpfs-site.xml ${HADOOP_ROOT}/etc/hadoop/httpfs-site.xml
COPY --chown=gpadmin:gpadmin entrypoint.sh ${GPHD_ROOT}/bin/testcontainers-entrypoint.sh

RUN chmod +x ${GPHD_ROOT}/bin/* \
    && mkdir -p ${GPHD_ROOT}/storage/{hadoop,hive,logs,pids} \
    && chown -R gpadmin:gpadmin ${GPHD_ROOT}

USER gpadmin
WORKDIR /home/gpadmin
EXPOSE 8020 50010 9083 10000 14000
ENTRYPOINT ["/home/gpadmin/workspace/singlecluster/bin/testcontainers-entrypoint.sh"]
