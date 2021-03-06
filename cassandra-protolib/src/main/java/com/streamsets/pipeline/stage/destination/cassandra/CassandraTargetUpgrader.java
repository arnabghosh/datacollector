/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.cassandra;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.ProtocolVersion;
import com.streamsets.pipeline.api.Config;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.StageUpgrader;
import com.streamsets.pipeline.api.impl.Utils;

import java.util.List;
import java.util.stream.Collectors;

public class CassandraTargetUpgrader implements StageUpgrader {
  @Override
  public List<Config> upgrade(
      String library,
      String stageName,
      String stageInstance,
      int fromVersion,
      int toVersion,
      List<Config> configs
  ) throws StageException {
    List<Config> newConfigs = configs;
    switch(fromVersion) {
      case 1:
        upgradeV1ToV2(configs);
        if (toVersion == 2) {
          break;
        }
        // fall through
      case 2:
        upgradeV2ToV3(configs);
        if (toVersion == 3) {
          break;
        }
        // fall through
      case 3:
        newConfigs = upgradeV3ToV4(configs);
        break;
      default:
        throw new IllegalStateException(Utils.format("Unexpected fromVersion {}", fromVersion));
    }
    return newConfigs;
  }

  private void upgradeV1ToV2(List<Config> configs) {
    configs.add(new Config("compression", CassandraCompressionCodec.NONE));
  }

  private void upgradeV2ToV3(List<Config> configs) {
    configs.add(new Config("protocolVersion", ProtocolVersion.V3));
  }

  private List<Config> upgradeV3ToV4(List<Config> configs) {
    List<Config> newConfigs = configs
        .stream()
        .map(config -> new Config("conf." + config.getName(), config.getValue()))
        .collect(Collectors.toList());

    newConfigs.add(new Config("conf.batchType", BatchStatement.Type.LOGGED));
    newConfigs.add(new Config("conf.maxBatchSize", 65535));

    return newConfigs;
  }
}
