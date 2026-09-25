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

SHELL := /bin/bash
PG_CONFIG ?= pg_config
PG_MAJOR ?= $(shell $(PG_CONFIG) --version | sed -E 's/.* ([0-9]+)\..*/\1/')
PROFILE ?= release
PYTHON ?= python3
CARGO ?= cargo

.PHONY: all external-table fdw test-core fmt check-fmt stage-external-table stage-fdw check-config smoke
all: external-table fdw

check-config:
	$(PYTHON) scripts/build.py check --pg-config "$(PG_CONFIG)" --pg-major "$(PG_MAJOR)"

external-table: build-external-table
fdw: build-fdw

build-external-table build-fdw: check-config
	$(PYTHON) scripts/build.py build --extension $(@:build-%=%) --pg-config "$(PG_CONFIG)" --pg-major "$(PG_MAJOR)" --profile "$(PROFILE)" --cargo "$(CARGO)"

stage-external-table stage-fdw install-external-table install-fdw: check-config
	$(PYTHON) scripts/build.py $(firstword $(subst -, ,$@)) --extension $(patsubst install-%,%,$(patsubst stage-%,%,$@)) --pg-config "$(PG_CONFIG)" --pg-major "$(PG_MAJOR)" --profile "$(PROFILE)" --cargo "$(CARGO)"

test-core:
	$(CARGO) test --locked -p pxf-core

fmt:
	$(CARGO) fmt --all

check-fmt:
	$(CARGO) fmt --all --check

# Requires artifacts installed in a DISPOSABLE cluster.
# The smoke script creates and removes only its own fresh test database.
smoke:
	bash tests/smoke.sh

.PHONY: build-external-table build-fdw install-external-table install-fdw clean clean-external-table clean-fdw regress-fdw regress-external-table integration
clean clean-external-table clean-fdw:
	rm -rf target build external-table/build fdw/build

regress-fdw regress-external-table:
	PG_CONFIG="$(PG_CONFIG)" bash tests/regress.sh $(@:regress-%=%)

integration:
	$(PYTHON) tests/integration.py
