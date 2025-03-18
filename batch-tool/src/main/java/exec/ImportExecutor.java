/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package exec;

import cmd.BaseOperateCommand;
import com.alibaba.druid.pool.DruidDataSource;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WorkerPool;
import datasource.DataSourceConfig;
import exception.DatabaseException;
import model.config.BenchmarkMode;
import model.config.ConfigConstant;
import model.config.DdlMode;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.CountStat;
import util.DbUtil;
import util.SyncUtil;
import worker.MyThreadPool;
import worker.MyWorkerPool;
import worker.ddl.DdlImportWorker;
import worker.insert.DirectImportWorker;
import worker.insert.ImportConsumer;
import worker.insert.ProcessOnlyImportConsumer;
import worker.insert.ShardedImportConsumer;
import worker.tpch.consumer.TpchInsertConsumer;
import worker.tpch.model.BatchInsertSqlEvent;
import worker.tpch.pruducer.TpchImportProducer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

public class ImportExecutor extends WriteDbExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ImportExecutor.class);

    public ImportExecutor(DataSourceConfig dataSourceConfig,
                          DruidDataSource druid,
                          BaseOperateCommand baseCommand) {
        super(dataSourceConfig, druid, baseCommand);
    }

    @Override
    protected void handleSingleTableInner(String tableName) throws Exception {
        if (producerExecutionContext.isSingleThread()
            && consumerExecutionContext.isSingleThread()) {
            // 使用按行读取insert模式
            doSingleThreadImport(tableName);
        } else {
            if (command.isShardingEnabled()) {
                doShardingImport(tableName);
            } else {
                doDefaultImport(tableName);
            }
        }

        if (producerExecutionContext.getException() != null) {
            throw producerExecutionContext.getException();
        }
        if (consumerExecutionContext.getException() != null) {
            throw consumerExecutionContext.getException();
        }
    }

    @Override
    public void preCheck() {
        if (producerExecutionContext.getDdlMode() == DdlMode.NO_DDL) {
            if (command.isDbOperation()) {
                try (Connection conn = dataSource.getConnection()) {
                    this.tableNames = DbUtil.getAllTablesInDb(conn, command.getDbName());
                } catch (SQLException | DatabaseException e) {
                    throw new RuntimeException(e);
                }
            } else {
                checkTableExists(command.getTableNames());
                this.tableNames = command.getTableNames();
            }
        }
        if (CollectionUtils.isNotEmpty(tableNames)) {
            logger.info("目标导入表：{}", tableNames);
        }
    }

    private void checkDbNotExist(String dbName) {
        if (ConfigConstant.DEFAULT_SCHEMA_NAME.equalsIgnoreCase(dbName)) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            if (DbUtil.checkDatabaseExists(conn, dbName)) {
               throw new RuntimeException(String.format("Database [%s] already exists, cannot import with ddl",
                   dbName));
            }
        } catch (SQLException | DatabaseException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkTableNotExist(List<String> tableNames) {
        for (String tableName : tableNames) {
            try (Connection conn = dataSource.getConnection()) {
                if (DbUtil.checkTableExists(conn, tableName)) {
                    throw new RuntimeException(String.format("Table [%s] already exists, cannot import with ddl",
                        tableNames));
                }
            } catch (SQLException | DatabaseException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void execute() {
        if (producerExecutionContext.getBenchmarkMode() != BenchmarkMode.NONE) {
            handleBenchmark(tableNames);
            return;
        }

        switch (producerExecutionContext.getDdlMode()) {
        case WITH_DDL:
            handleDDL();
            if (command.isDbOperation()) {
                // 库级别导入模式下更新导入的目标表
                try (Connection conn = dataSource.getConnection()) {
                    this.tableNames = DbUtil.getAllTablesInDb(conn, command.getDbName());
                } catch (SQLException | DatabaseException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new IllegalStateException("Do not support importing to table with DDL");
            }
            break;
        case DDL_ONLY:
            handleDDL();
            return;
        case NO_DDL:
            break;
        default:
            throw new UnsupportedOperationException("DDL mode is not supported: " +
                producerExecutionContext.getDdlMode());
        }

        if (CollectionUtils.isEmpty(tableNames)) {
            logger.warn("目标表未设置");
            return;
        }

        configureFieldMetaInfo();

        for (String tableName : tableNames) {
            logger.info("开始导入表：{}", tableName);
            try {
                handleSingleTable(tableName);
                logger.info("导入数据到 {} 完成，导入计数：{}", tableName, CountStat.getDbRowCount());
            } catch (Exception e) {
                logger.error("导入数据到 {} 失败：{}", tableName, e.getMessage());
            }
        }
    }

    private void handleBenchmark(List<String> tableNames) {
        switch (producerExecutionContext.getBenchmarkMode()) {
        case TPCH:
            handleTpchImport(tableNames);
            break;
        default:
            throw new UnsupportedOperationException("Not support " + producerExecutionContext.getBenchmarkMode());
        }
    }

    private void handleTpchImport(List<String> tableNames) {
        int producerParallelism = producerExecutionContext.getParallelism();
        AtomicInteger emittedDataCounter = SyncUtil.newRemainDataCounter();

        ThreadPoolExecutor producerThreadPool = MyThreadPool.createExecutorExact(TpchImportProducer.class.getSimpleName(),
            producerParallelism);
        producerExecutionContext.setProducerExecutor(producerThreadPool);
        producerExecutionContext.setEmittedDataCounter(emittedDataCounter);

        int consumerParallelism = getConsumerNum(consumerExecutionContext);
        consumerExecutionContext.setParallelism(consumerParallelism);
        consumerExecutionContext.setDataSource(dataSource);
        consumerExecutionContext.setEmittedDataCounter(emittedDataCounter);
        ThreadPoolExecutor consumerThreadPool = MyThreadPool.createExecutorExact(TpchInsertConsumer.class.getSimpleName(),
            consumerParallelism);

        EventFactory<BatchInsertSqlEvent> factory = BatchInsertSqlEvent::new;
        RingBuffer<BatchInsertSqlEvent> ringBuffer = MyWorkerPool.createRingBuffer(factory);
        TpchImportProducer tpchProducer = new TpchImportProducer(producerExecutionContext, tableNames, ringBuffer);
        CountDownLatch countDownLatch = SyncUtil.newMainCountDownLatch(tpchProducer.getWorkerCount());
        producerExecutionContext.setCountDownLatch(countDownLatch);

        TpchInsertConsumer[] consumers = new TpchInsertConsumer[consumerParallelism];
        try {
            for (int i = 0; i < consumerParallelism; i++) {
                consumers[i] = new TpchInsertConsumer(consumerExecutionContext);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }

        logger.debug("producer config {}", producerExecutionContext);
        logger.debug("consumer config {}", consumerExecutionContext);

        // 开启线程工作
        WorkerPool<BatchInsertSqlEvent> workerPool = MyWorkerPool.createWorkerPool(ringBuffer, consumers);
        workerPool.start(consumerThreadPool);
        try {
            tpchProducer.produce();
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }

        waitAndShutDown(countDownLatch, emittedDataCounter, producerThreadPool, consumerThreadPool,
            workerPool);
    }

    /**
     * 同步导入建库建表语句
     */
    private void handleDDL() {
        DdlImportWorker ddlImportWorker;
        if (command.isDbOperation()) {
            ddlImportWorker =
                DdlImportWorker.fromFiles(producerExecutionContext.getDdlFileLineRecordList(), dataSource);
        } else {
            ddlImportWorker = DdlImportWorker.fromTables(command.getTableNames(), dataSource);
        }
        ddlImportWorker.doImportSync();
    }

    private void doSingleThreadImport(String tableName) {
        DirectImportWorker directImportWorker = new DirectImportWorker(dataSource, tableName,
            producerExecutionContext, consumerExecutionContext);
        Thread importThread = new Thread(directImportWorker);
        importThread.start();
        try {
            importThread.join();
        } catch (InterruptedException e) {
            logger.error("Interrupted when waiting for finish", e);
        }
    }

    private void doDefaultImport(String tableName) {
        if (consumerExecutionContext.isReadProcessFileOnly()) {
            // 测试读取文件的性能
            configureCommonContextAndRun(ProcessOnlyImportConsumer.class,
                producerExecutionContext, consumerExecutionContext, tableName, false);
        } else {
            configureCommonContextAndRun(ImportConsumer.class,
                producerExecutionContext, consumerExecutionContext, tableName,
                useBlockReader());
        }
    }

    private void doShardingImport(String tableName) {
        configurePartitionKey();
        configureTopology();

        configureCommonContextAndRun(ShardedImportConsumer.class,
            producerExecutionContext, consumerExecutionContext, tableName,
            useBlockReader());
    }

}
