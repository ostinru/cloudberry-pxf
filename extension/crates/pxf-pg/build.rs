// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=src/cloudberry.c");
    println!("cargo:rerun-if-env-changed=PGRX_PG_CONFIG_PATH");
    let pg_config = std::env::var("PGRX_PG_CONFIG_PATH").expect("PGRX_PG_CONFIG_PATH is required");
    let include = Command::new(&pg_config)
        .arg("--includedir-server")
        .output()
        .expect("pg_config");
    assert!(
        include.status.success(),
        "pg_config --includedir-server failed"
    );
    let include = String::from_utf8(include.stdout).expect("UTF-8 header path");
    cc::Build::new()
        .file("src/cloudberry.c")
        .include(include.trim())
        .flag_if_supported("-Wno-unused-parameter")
        .compile("pxf_cloudberry_abi");
}
