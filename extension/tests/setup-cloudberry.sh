#!/usr/bin/env bash
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

# Run only in a fresh automation testcontainer, as gpadmin.
set -eo pipefail

sudo mkdir -p /run/sshd
sudo ssh-keygen -A
sudo /usr/sbin/sshd
mkdir -p "$HOME/.ssh"
if [[ ! -f "$HOME/.ssh/id_rsa" ]]; then
    ssh-keygen -q -t rsa -N '' -f "$HOME/.ssh/id_rsa"
fi
cat "$HOME/.ssh/id_rsa.pub" >> "$HOME/.ssh/authorized_keys"
chmod 600 "$HOME/.ssh/authorized_keys"
ssh-keyscan -H mdw localhost >> "$HOME/.ssh/known_hosts" 2>/dev/null

# Same demo cluster and installed Cloudberry as PXFCloudberryContainer.
# Do not call make clean or the automation cleanup function: a pre-existing
# cluster is an error rather than permission to delete somebody's data.
demo="$HOME/workspace/cloudberry/gpAux/gpdemo"
if [[ -d "$demo/datadirs" ]]; then
    echo 'Expected a fresh container; demo datadirs already exist' >&2
    exit 1
fi
source /usr/local/cloudberry-db/cloudberry-env.sh
make -C "$HOME/workspace/cloudberry" create-demo-cluster
