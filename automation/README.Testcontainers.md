# TestContainers-based Automation Tests

Host-driven integration tests that use [TestContainers](https://www.testcontainers.org/)
to manage a `pxf/singlecluster:3` Docker container with Cloudberry DB + PXF.

## Prerequisites

1. **Docker** running on the host.
2. **Docker image** `pxf/singlecluster:3` available locally
   (build with `make -C ci/docker` or pull from registry).
3. **Cloudberry source** at `../cloudberry/`.
4. **PXF server built** — run `cd server && ./gradlew build` so that PXF JARs
   exist in `server/pxf-*/build/libs/`. The automation Gradle build auto-copies
   them via the `ensurePxfJars` task.
5. **Java 11+** on the host.
6. *(Optional)* **Cloudberry `.deb` package** in `/tmp/apache-cloudberry-db*.deb`.
   If absent, the container builds Cloudberry from source automatically.

## Running tests

From the `automation/` directory:

```bash
# Run all JDBC tests
./gradlew test -Dgroups=jdbc

# Run a single test method
./gradlew test -Dgroups=jdbc \
  --tests 'org.apache.cloudberry.pxf.automation.features.jdbc.JdbcTest.singleFragmentTable'
```

### Optional system properties

| Property                     | Default                                    | Description                            |
|------------------------------|--------------------------------------------|----------------------------------------|
| `pxf.test.repo.path`        | auto-detected (walks up from `automation/`)| Path to `cloudberry-pxf` repo root     |
| `pxf.test.cloudberry.path`  | `../cloudberry` relative to repo root      | Path to Cloudberry source directory    |
| `pxf.test.deb.path`         | auto-detected from `/tmp/`                 | Path to Cloudberry `.deb` (optional)   |

Example with explicit paths:

```bash
./gradlew test -Dgroups=jdbc \
  -Dpxf.test.repo.path=/path/to/cloudberry-pxf \
  -Dpxf.test.cloudberry.path=/path/to/cloudberry \
  -Dpxf.test.deb.path=/path/to/cloudberry-db.deb
```

## What happens at runtime

1. `PXFCBDBContainer.getInstance()` starts a `pxf/singlecluster:3` Docker container:
   - `cloudberry-pxf` repo bind-mounted at `/home/gpadmin/workspace/cloudberry-pxf`
   - `cloudberry` source bind-mounted at `/home/gpadmin/workspace/cloudberry`
   - `.deb` package copied into the container (if available)
   - Ports 7000 (GPDB) and 5888 (PXF) exposed with random host-mapped ports
2. `entrypoint.sh` runs inside the container — installs/builds Cloudberry DB,
   builds PXF, starts Hadoop/Hive/HBase/MinIO, starts GPDB and PXF (~5–10 min).
3. JDBC servers (`database`, `db-session-params`) are configured and PXF restarts.
4. `JdbcTest.setup()` connects to GPDB via the mapped port, creates tables,
   and loads test data using PostgreSQL `CopyManager`.
5. Each `@Test` method runs a `pxf_regress` SQL test inside the container.
6. The container stays alive for the entire test run (singleton) and stops on
   JVM shutdown.

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
  │     ├── runs entrypoint.sh        (installs CBDB, builds PXF, starts services)
  │     └── configures JDBC servers   (database, db-session-params)
  ├── CbdbApplication                 (JDBC client from host via mapped port)
  │     └── uses CopyManager          (efficient bulk data loading)
  └── RegressApplication              (runs pxf_regress binary inside container)
```

## Files

| File | Description |
|------|-------------|
| `src/test/java/.../testcontainers/PXFCBDBContainer.java` | Singleton Docker container management |
| `src/test/java/.../testcontainers/CbdbApplication.java` | Host-side JDBC CBDB client |
| `src/test/java/.../testcontainers/RegressApplication.java` | Runs `pxf_regress` inside container |
| `src/test/java/.../features/jdbc/JdbcTest.java` | 14 JDBC tests |
