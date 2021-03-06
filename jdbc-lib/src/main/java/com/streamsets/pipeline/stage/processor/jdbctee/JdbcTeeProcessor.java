/**
 * Copyright 2016 StreamSets Inc.
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
package com.streamsets.pipeline.stage.processor.jdbctee;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Processor;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.SingleLaneProcessor;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.lib.el.ELUtils;
import com.streamsets.pipeline.lib.jdbc.*;
import com.streamsets.pipeline.lib.operation.UnsupportedOperationAction;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import com.streamsets.pipeline.stage.destination.jdbc.Groups;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class JdbcTeeProcessor extends SingleLaneProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(JdbcTeeProcessor.class);

  private static final String HIKARI_CONFIG_PREFIX = "hikariConfigBean.";
  private static final String CONNECTION_STRING = HIKARI_CONFIG_PREFIX + "connectionString";

  private final boolean rollbackOnError;
  private final boolean useMultiRowOp;
  private final int maxPrepStmtParameters;
  private final int maxPrepStmtCache;

  private final String schema;
  private final String tableNameTemplate;
  private final List<JdbcFieldColumnParamMapping> customMappings;
  private final List<JdbcFieldColumnMapping> generatedColumnMappings;
  private final boolean caseSensitive;

  private final Properties driverProperties = new Properties();
  private final ChangeLogFormat changeLogFormat;
  private final HikariPoolConfigBean hikariConfigBean;

  private ELEval tableNameEval = null;
  private ELVars tableNameVars = null;

  private ErrorRecordHandler errorRecordHandler;
  private HikariDataSource dataSource = null;
  private Connection connection = null;

  private JDBCOperationType defaultOperation;
  private UnsupportedOperationAction unsupportedAction;

  public JdbcTeeProcessor(
      String schema,
      String tableNameTemplate,
      List<JdbcFieldColumnParamMapping> customMappings,
      List<JdbcFieldColumnMapping> generatedColumnMappings,
      boolean caseSensitive,
      boolean rollbackOnError,
      boolean useMultiRowOp,
      int maxPrepStmtParameters,
      int maxPrepStmtCache,
      ChangeLogFormat changeLogFormat,
      HikariPoolConfigBean hikariConfigBean,
      JDBCOperationType defaultOp,
      UnsupportedOperationAction unsupportedAction
  ) {
    this.schema = schema;
    this.tableNameTemplate = tableNameTemplate;
    this.customMappings = customMappings;
    this.generatedColumnMappings = generatedColumnMappings;
    this.caseSensitive = caseSensitive;
    this.rollbackOnError = rollbackOnError;
    this.useMultiRowOp = useMultiRowOp;
    this.maxPrepStmtParameters = maxPrepStmtParameters;
    this.maxPrepStmtCache = maxPrepStmtCache;
    this.driverProperties.putAll(hikariConfigBean.driverProperties);
    this.changeLogFormat = changeLogFormat;
    this.hikariConfigBean = hikariConfigBean;
    this.defaultOperation = defaultOp;
    this.unsupportedAction = unsupportedAction;
  }

  class RecordWriterLoader extends CacheLoader<String, JdbcRecordWriter> {
    @Override
    public JdbcRecordWriter load(String tableName) throws Exception {
      return JdbcRecordReaderWriterFactory.createJdbcRecordWriter(
          hikariConfigBean.connectionString,
          dataSource,
          tableName,
          customMappings,
          generatedColumnMappings,
          rollbackOnError,
          useMultiRowOp,
          maxPrepStmtParameters,
          maxPrepStmtCache,
          defaultOperation,
          unsupportedAction,
          JdbcRecordReaderWriterFactory.createRecordReader(changeLogFormat)
      );
    }
  }

  private final LoadingCache<String, JdbcRecordWriter> recordWriters = CacheBuilder.newBuilder()
      .maximumSize(500)
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build(new RecordWriterLoader());

  /** {@inheritDoc} */
  @Override
  protected List<ConfigIssue> init() {
    // Validate configuration values and open any required resources.
    List<ConfigIssue> issues = super.init();

    errorRecordHandler = new DefaultErrorRecordHandler(getContext());
    Processor.Context context = getContext();

    issues = hikariConfigBean.validateConfigs(context, issues);

    tableNameVars = getContext().createELVars();
    tableNameEval = context.createELEval(JdbcUtil.TABLE_NAME);
    ELUtils.validateExpression(tableNameEval,
        tableNameVars,
        tableNameTemplate,
        getContext(),
        Groups.JDBC.getLabel(),
        JdbcUtil.TABLE_NAME,
        JdbcErrors.JDBC_26,
        String.class,
        issues
    );

    if (issues.isEmpty() && null == dataSource) {
      try {
        dataSource = JdbcUtil.createDataSourceForWrite(hikariConfigBean,
            driverProperties,
            tableNameTemplate,
            issues,
            customMappings,
            getContext()
        );
      } catch (RuntimeException | SQLException e) {
        LOG.debug("Could not connect to data source", e);
        issues.add(getContext().createConfigIssue(Groups.JDBC.name(), CONNECTION_STRING, JdbcErrors.JDBC_00, e.toString()));
      }
    }

    // If issues is not empty, the UI will inform the user of each configuration issue in the list.
    return issues;
  }

  /** {@inheritDoc} */
  @Override
  public void destroy() {
    JdbcUtil.closeQuietly(connection);
    JdbcUtil.closeQuietly(dataSource);
    super.destroy();
  }

  /** {@inheritDoc} */
  @Override
  public void process(Batch batch, SingleLaneBatchMaker batchMaker) throws StageException {
    JdbcUtil.write(batch, schema, tableNameEval, tableNameVars, tableNameTemplate, caseSensitive, recordWriters, errorRecordHandler);

    Iterator<Record> it = batch.getRecords();
    while (it.hasNext()) {
      batchMaker.addRecord(it.next());
    }
  }
}