# TestContainers-based Automation Tests

Host-driven integration tests that use [TestContainers](https://www.testcontainers.org/)
to manage Docker containers for Cloudberry DB + PXF and supporting services (MinIO, etc.).

## Prerequisites

1. **Docker** running on the host.
2. **Docker image** `pxf/cbdb-dev:1` available locally (see [Building the image](#building-the-image) below).
3. **Docker image** `minio/minio:RELEASE.2023-09-04T19-57-37Z` (pulled automatically
   on first run if not present).
4. **Cloudberry source** at `../cloudberry/`.
5. **PXF server built** — run `cd server && ./gradlew build` so that PXF JARs
   exist in `server/pxf-*/build/libs/`. The automation Gradle build auto-copies
   them via the `ensurePxfJars` task.
6. **Java 11+** on the host.

### Building the image

The `pxf/cbdb-dev:1` image is a multistage build that includes:
- **Hadoop/Hive/HBase/ZK/Tez stack** copied from `pxf/singlecluster:3`
- **Cloudberry DB pre-built** from source (demo cluster is created at runtime)

```bash
# 1. Build the singlecluster base image (if not already built)
docker build -t pxf/singlecluster:3 ci/singlecluster/

# 2. Build the cbdb-dev image (includes pre-compiled Cloudberry)
docker build -t pxf/cbdb-dev:1 ci/docker/pxf-cbdb-dev/ubuntu/

# Override cloudberry branch:
docker build --build-arg CLOUDBERRY_BRANCH=my-branch \
  -t pxf/cbdb-dev:1 ci/docker/pxf-cbdb-dev/ubuntu/
```

## Running tests

From the `automation/` directory:

```bash
# Run all JDBC tests
./gradlew test -Dgroups=jdbc

# Run all S3 tests (uses MinIO container)
./gradlew test -Dgroups=s3

# Run a single test method
./gradlew test -Dgroups=jdbc \
  --tests 'org.apache.cloudberry.pxf.automation.features.jdbc.JdbcTest.singleFragmentTable'

# Run a single S3 test method
./gradlew test -Dgroups=s3 \
  --tests 'org.apache.cloudberry.pxf.automation.features.cloud.S3SelectTest.testPlainCsvWithHeaders'
```

### Optional system properties

| Property                     | Default                                    | Description                            |
|------------------------------|--------------------------------------------|----------------------------------------|
| `pxf.test.repo.path`        | auto-detected (walks up from `automation/`)| Path to `cloudberry-pxf` repo root     |
| `pxf.test.cloudberry.path`  | `../cloudberry` relative to repo root      | Path to Cloudberry source directory    |
| `pxf.test.deb.path`         | auto-detected from `/tmp/`                 | Path to Cloudberry `.deb` (optional)   |

Example with explicit paths:

```bash
./gradlew test -Dgroups=s3 \
  -Dpxf.test.repo.path=/path/to/cloudberry-pxf \
  -Dpxf.test.cloudberry.path=/path/to/cloudberry \
  -Dpxf.test.deb.path=/path/to/cloudberry-db.deb
```

## What happens at runtime

### JDBC tests (`-Dgroups=jdbc`)

1. `PXFCBDBContainer.getInstance()` starts a `pxf/cbdb-dev:1` Docker container:
   - Cloudberry DB is **pre-built** in the image (no compilation needed)
   - `cloudberry-pxf` repo bind-mounted at `/home/gpadmin/workspace/cloudberry-pxf`
   - `cloudberry` source bind-mounted at `/home/gpadmin/workspace/cloudberry`
   - Ports 7000 (CBDB) and 5888 (PXF) exposed with random host-mapped ports
2. `entrypoint.sh` runs inside the container — creates the demo cluster,
   builds PXF, starts Hadoop/Hive/HBase, starts CBDB and PXF (~1-2 min).
3. JDBC servers (`database`, `db-session-params`) are configured and PXF restarts.
4. `JdbcTest.setup()` connects to CBDB via the mapped port, creates tables,
   and loads test data using PostgreSQL `CopyManager`.
5. Each `@Test` method runs a `pxf_regress` SQL test inside the container.
6. Both containers stay alive for the entire test run (singletons) and stop on
   JVM shutdown.

### S3 tests (`-Dgroups=s3`)

1. `PXFCBDBContainer` starts as above (shared singleton, `pxf/cbdb-dev:1`).
2. `MinIOContainer` starts a `minio/minio` container on the same Docker network:
   - Credentials: `admin` / `password`
   - Bucket `gpdb-ud-scratch` is created automatically
   - Network alias `minio` allows PXF to reach it at `http://minio:9000`
3. `configureS3Servers()` writes PXF S3 server configs (`s3`, `s3-invalid`, `default`)
   pointing to `http://minio:9000`, removes HDFS/Hive/HBase configs from the
   default server, and restarts PXF.
4. Test code on the host uploads data to MinIO via `http://localhost:<mapped-port>`
   using the Hadoop S3A filesystem client.
5. Each `@Test` creates external tables with `s3:*` profiles and runs `pxf_regress`.

## Test results

```bash
# Gradle HTML report
open build/reports/tests/test/index.html

# XML results (for CI)
ls build/test-results/test/TEST-*.xml
```

## Architecture

```
JdbcTest                              (TestNG test class, group "jdbc")
  ├── PXFCBDBContainer                (GenericContainer singleton — starts Docker)
  │     ├── runs entrypoint.sh        (creates demo cluster, builds PXF, starts services)
  │     └── configures JDBC servers   (database, db-session-params)
  ├── CbdbApplication                 (JDBC client from host via mapped port)
  │     └── uses CopyManager          (efficient bulk data loading)
  └── RegressApplication              (runs pxf_regress binary inside container)

S3SelectTest / CloudAccessTest        (TestNG test classes, group "s3")
  ├── PXFCBDBContainer                (shared singleton — same as JDBC)
  │     └── configures S3 servers     (s3, s3-invalid, default → MinIO endpoint)
  ├── MinIOContainer                  (minio/minio container on shared network)
  │     └── bucket gpdb-ud-scratch    (created on startup via AWS SDK)
  ├── CbdbApplication                 (JDBC client for table DDL)
  ├── RegressApplication              (runs pxf_regress SQL tests)
  └── Hdfs (S3A)                      (uploads test data from host to MinIO)
```

## Docker network

All test containers share a Docker network so they can communicate by hostname:

```
┌─────────────────────────────────┐
│       Shared Docker Network     │
│                                 │
│  ┌──────────────┐  ┌─────────┐ │
│  │ PXFCBDBCont. │  │  MinIO  │ │
│  │ alias: mdw   │──│ alias:  │ │
│  │ :7000 :5888  │  │ minio   │ │
│  └──────────────┘  │ :9000   │ │
│                    └─────────┘ │
└─────────────────────────────────┘
        ↕ mapped ports ↕
   ┌─────────────────────┐
   │    Host (tests)     │
   │  JDBC → :mapped     │
   │  S3A  → :mapped     │
   └─────────────────────┘
```

## Files

| File | Description |
|------|-------------|
| `src/test/java/.../testcontainers/PXFCBDBContainer.java` | Singleton CBDB+PXF Docker container management |
| `src/test/java/.../testcontainers/MinIOContainer.java` | Singleton MinIO Docker container (S3-compatible) |
| `src/test/java/.../testcontainers/CbdbApplication.java` | Host-side JDBC CBDB client |
| `src/test/java/.../testcontainers/RegressApplication.java` | Runs `pxf_regress` inside container |
| `src/test/java/.../features/jdbc/JdbcTest.java` | 14 JDBC tests |
| `src/test/java/.../features/cloud/S3SelectTest.java` | 10 S3 Select tests (CSV, Parquet) |
| `src/test/java/.../features/cloud/CloudAccessTest.java` | 6 S3 cloud access tests + 7 HDFS+S3 tests |
