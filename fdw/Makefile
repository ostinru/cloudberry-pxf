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

# Compatibility entry point. Production implementation lives in extension/.
.DEFAULT_GOAL := all
PG_CONFIG ?= pg_config
PROFILE ?= release
.PHONY: all install stage clean clean-all installcheck test
all install stage:
	$(MAKE) -C ../extension $(@:all=build)-fdw PG_CONFIG="$(PG_CONFIG)" PROFILE="$(PROFILE)"
clean clean-all:
	$(MAKE) -C ../extension clean-fdw
test installcheck:
	$(MAKE) -C ../extension regress-fdw PG_CONFIG="$(PG_CONFIG)"
