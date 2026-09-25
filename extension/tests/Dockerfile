# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

# This is the image used by automation's PXFCloudberryContainer.
ARG BASE_IMAGE=pxf/cbdb-testcontainer-ubuntu:2
FROM ${BASE_IMAGE}
RUN sudo apt-get update -qq && \
    sudo apt-get install -y --no-install-recommends \
        clang libclang-dev libssl-dev pkg-config && \
    sudo rm -rf /var/lib/apt/lists/*
ARG RUST_VERSION=1.85.1
RUN curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs -o /tmp/rustup-init.sh && \
    sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain ${RUST_VERSION} \
        --component rustfmt --component clippy && \
    rm /tmp/rustup-init.sh
ENV PATH=/home/gpadmin/.cargo/bin:${PATH}
