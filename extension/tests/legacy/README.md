<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Frozen C compatibility fixtures

These are the C extensions and regression cases from the `rust` branch baseline
(commit `12ae4ec8`). They are built only by `tests/install-legacy.sh` for historical-version,
C/Rust benchmark and upgrade tests. Their SQL/expected directories also supply the
original behavior regression tests against the new libraries.

Production builds, installation and packaging use the Rust packages in
`extension/external-table` and `extension/fdw`. Do not add production fixes here.
The only baseline build change is the relative path to the root `api_version`.
