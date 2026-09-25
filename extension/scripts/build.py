#!/usr/bin/env python3
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

"""Independent, explicitly targeted builds of the two Cloudberry extensions."""

import argparse
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {"external-table": "pxf", "fdw": "pxf_fdw"}


def config_value(pg_config, option):
    return subprocess.check_output([pg_config, option], text=True).strip()


def atomic_install(source, destination, mode):
    # An already loaded .so must retain its inode until its backend exits.
    # Never truncate/overwrite the mapped library during an upgrade.
    with tempfile.NamedTemporaryFile(prefix=".pxf-install-", dir=destination.parent, delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        shutil.copy2(source, temporary_path)
        temporary_path.chmod(mode)
        os.replace(temporary_path, destination)
    finally:
        temporary_path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "build", "stage", "install"])
    parser.add_argument("--extension", choices=PACKAGES)
    parser.add_argument("--pg-config", default="pg_config")
    parser.add_argument("--pg-major", required=True, choices=["14", "16"])
    parser.add_argument("--profile", default="release", choices=["debug", "release"])
    parser.add_argument("--cargo", default="cargo")
    parser.add_argument("--destdir", default=os.environ.get("DESTDIR", ""))
    args = parser.parse_args()
    pg_config = shutil.which(args.pg_config)
    if not pg_config:
        parser.error("pg_config is not executable: " + args.pg_config)
    pg_config = str(Path(pg_config).resolve())
    version = config_value(pg_config, "--version")
    match = re.search(r"\b(\d+)\.\d+", version)
    if not match or match[1] != args.pg_major:
        parser.error("requested pg" + args.pg_major + " but pg_config reports " + version)
    headers = Path(config_value(pg_config, "--includedir-server"))
    fmgr = headers / "fmgr.h"
    if not fmgr.is_file() or "PgMagicProductCloudberry" not in fmgr.read_text():
        parser.error("the cbdb pgrx feature requires Cloudberry server headers")
    if args.action == "check":
        print("Cloudberry headers:", headers, "kernel:", version)
        return
    if not args.extension:
        parser.error("--extension is required for build and stage")
    target = ROOT / "target" / ("pg" + args.pg_major)
    env = dict(os.environ, PGRX_PG_CONFIG_PATH=pg_config, CARGO_TARGET_DIR=str(target))
    package = PACKAGES[args.extension]
    cmd = [args.cargo, "build", "--locked", "--manifest-path", str(ROOT / "Cargo.toml"),
           "-p", package, "--no-default-features", "--features", "pg" + args.pg_major, "--lib"]
    if args.profile == "release":
        cmd.append("--release")
    subprocess.run(cmd, env=env, cwd=ROOT, check=True)
    if args.action == "build":
        return
    library = target / args.profile / ("lib" + package + (".dylib" if sys.platform == "darwin" else ".so"))
    stage = ROOT / "build" / ("pg" + args.pg_major) / args.extension
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, stage / (package + "_rust.so"))
    shutil.copy2(ROOT / args.extension / (package + ".control"), stage)
    for sql in (ROOT / args.extension / "sql").glob("*.sql"):
        shutil.copy2(sql, stage)
    # Preserve the package layout consumed by the CLI, RPM and DEB installers.
    package_dir = ROOT / args.extension / "build" / "stage" / ("gpextable" if args.extension == "external-table" else "fdw")
    if package_dir.exists():
        shutil.rmtree(package_dir)
    shutil.copytree(stage, package_dir)
    makefile = Path(config_value(pg_config, "--pgxs")).parents[1] / "Makefile.global"
    variables = dict(re.findall(r"^(GP_VERSION|GP_MAJORVERSION)\s*=\s*(\S+)", makefile.read_text(), re.MULTILINE))
    if "GP_VERSION" not in variables or "GP_MAJORVERSION" not in variables:
        parser.error("Cloudberry version metadata missing from " + str(makefile))
    (package_dir / "metadata").write_text("cloudberry.version=" + variables["GP_VERSION"] + "\ncloudberry.major-version=" + variables["GP_MAJORVERSION"] + "\n")
    metadata = ROOT / args.extension / "build" / "metadata"
    metadata.mkdir(parents=True, exist_ok=True)
    (metadata / "gp_major_version").write_text(variables["GP_MAJORVERSION"] + "\n")
    (metadata / "build_arch").write_text(platform.machine() + "\n")
    if args.action == "install":
        library_dir = Path(args.destdir + config_value(pg_config, "--pkglibdir"))
        sql_dir = Path(args.destdir + config_value(pg_config, "--sharedir")) / "extension"
        library_dir.mkdir(parents=True, exist_ok=True)
        sql_dir.mkdir(parents=True, exist_ok=True)
        for artifact in stage.iterdir():
            destination = library_dir if artifact.suffix == ".so" else sql_dir
            atomic_install(artifact, destination / artifact.name, 0o755 if artifact.suffix == ".so" else 0o644)
        print("Installed extension:", package, "under", library_dir)
    else:
        print("Staged extension:", stage)


if __name__ == "__main__":
    main()
