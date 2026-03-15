<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Running Automation in Docker

There are two ways to run automation tests: **from the host** (TestContainers, recommended)
or **inside the container** (legacy). Both use the same `pxf/cbdb-dev:1` Docker image
which includes a pre-built Cloudberry DB and the Hadoop/Hive/HBase stack.

## Prerequisites

* Docker and Docker Compose installed
* Both `cloudberry-pxf` and `cloudberry` repositories cloned in the same parent directory

### Building Docker images

```bash
cd cloudberry-pxf

# 1. Build the singlecluster base image (Hadoop stack)
docker build -t pxf/singlecluster:3 ci/singlecluster/

# 2. Build the cbdb-dev image (pre-compiled Cloudberry + Hadoop stack)
docker build -t pxf/cbdb-dev:1 ci/docker/pxf-cbdb-dev/ubuntu/

# Override cloudberry branch if needed:
docker build --build-arg CLOUDBERRY_BRANCH=my-branch \
  -t pxf/cbdb-dev:1 ci/docker/pxf-cbdb-dev/ubuntu/
```

---

## Option A: Host-driven tests with TestContainers (recommended)

Run tests directly from the host machine. TestContainers manages the Docker
containers automatically. See [README.Testcontainers.md](README.Testcontainers.md)
for full details.

```bash
cd automation

# JDBC tests
./gradlew test -Dgroups=jdbc

# S3 tests (starts a MinIO container automatically)
./gradlew test -Dgroups=s3

# HBase tests (starts a standalone HBase container automatically)
./gradlew test -Dgroups=hbase

# Single test method
./gradlew test -Dgroups=jdbc \
  --tests 'org.apache.cloudberry.pxf.automation.features.jdbc.JdbcTest.singleFragmentTable'
```

---

## Option B: Running tests inside the container (legacy)

1. Stop and remove any existing containers and volumes:
   ```bash
   docker compose -f ci/docker/pxf-cbdb-dev/ubuntu/docker-compose.yml down -v
   ```

2. Build and start the containers:
   ```bash
   docker compose -f ci/docker/pxf-cbdb-dev/ubuntu/docker-compose.yml build
   docker compose -f ci/docker/pxf-cbdb-dev/ubuntu/docker-compose.yml up -d
   ```

3. Run the entrypoint script to set up the environment:
   ```bash
   docker exec pxf-cbdb-dev bash -lc \
      "cd /home/gpadmin/workspace/cloudberry-pxf/ci/docker/pxf-cbdb-dev/ubuntu && ./script/entrypoint.sh"
   ```

4. Execute the test suite:
   ```bash
   docker exec pxf-cbdb-dev bash -lc \
      "cd /home/gpadmin/workspace/cloudberry-pxf/ci/docker/pxf-cbdb-dev/ubuntu && ./script/run_tests.sh"
   ```
   You can run tests multiple times in one container.

## Troubleshooting

Jump into container: `docker compose ps` + `docker exec -it <id> bash`

Check logs:

* **PXF logs**: `/home/gpadmin/pxf-base/logs/`
* **Hadoop logs**: `/home/gpadmin/workspace/singlecluster/storage/logs/`