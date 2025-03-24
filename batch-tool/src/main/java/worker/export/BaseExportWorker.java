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

package worker.export;

import com.alibaba.fastjson2.JSONObject;
import model.config.CompressMode;
import model.config.FileFormat;
import model.config.GlobalVar;
import model.config.QuoteEncloseMode;
import model.db.FieldMetaInfo;
import model.db.TableFieldMetaInfo;
import model.db.TableTopology;
import model.mask.AbstractDataMasker;
import model.mask.DataMaskerFactory;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.CountStat;
import util.DataSourceUtil;
import util.FileUtil;
import util.IOUtil;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public abstract class BaseExportWorker implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(BaseExportWorker.class);

    protected final DataSource druid;
    protected final TableTopology topology;
    protected final TableFieldMetaInfo tableFieldMetaInfo;
    protected final byte[] separator;

    protected final List<byte[]> specialCharList;
    protected final QuoteEncloseMode quoteEncloseMode;
    protected final String logicalTableName;
    /**
     * 逻辑表的行数统计
     */
    protected final AtomicLong rowCountStat;

    protected CompressMode compressMode;
    protected FileFormat fileFormat;

    protected final List<Boolean> isStringTypeList;

    protected List<AbstractDataMasker> columnDataMaskerList;
    protected ByteArrayOutputStream os;
    protected int bufferedRowNum = 0;       // 已经缓存的行数


    protected boolean isWithLastSep = false;
    /**
     * 物理分片的行数统计
     */
    protected long rowCount = 0;

    protected BaseExportWorker(DataSource druid, String logicalTableName,
                               TableTopology topology,
                               TableFieldMetaInfo tableFieldMetaInfo,
                               String separator, QuoteEncloseMode quoteEncloseMode) {
        this(druid, logicalTableName, topology, tableFieldMetaInfo,
            separator, quoteEncloseMode, CompressMode.NONE, FileFormat.NONE);
    }

    protected BaseExportWorker(DataSource druid, String logicalTableName,
                               TableTopology topology,
                               TableFieldMetaInfo tableFieldMetaInfo,
                               String separator, QuoteEncloseMode quoteEncloseMode,
                               CompressMode compressMode, FileFormat fileFormat) {

        this.druid = druid;
        this.topology = topology;
        this.tableFieldMetaInfo = tableFieldMetaInfo;

        this.separator = separator.getBytes();
        this.specialCharList = new ArrayList<>();
        specialCharList.add(this.separator);
        specialCharList.add(FileUtil.CR_BYTE);
        specialCharList.add(FileUtil.LF_BYTE);
        specialCharList.add(FileUtil.DOUBLE_QUOTE_BYTE);

        this.quoteEncloseMode = quoteEncloseMode;
        this.isStringTypeList = tableFieldMetaInfo.getFieldMetaInfoList().stream()
            .map(info -> (info.getType() == FieldMetaInfo.Type.STRING))
            .collect(Collectors.toList());

        switch (compressMode) {
        case NONE:
        case GZIP:
            this.compressMode = compressMode;
            break;
        default:
            throw new IllegalArgumentException("Unsupported compression mode: " + compressMode.name());
        }
        this.fileFormat = fileFormat;
        this.logicalTableName = logicalTableName;
        this.rowCountStat = CountStat.getTableRowCount(logicalTableName);
    }

    protected void produceData() {
        String sql = getExportSql();

        try (Connection conn = druid.getConnection();
            Statement stmt = DataSourceUtil.createStreamingStatement(conn);
            ResultSet resultSet = stmt.executeQuery(sql)) {

            logger.info("{} 开始执行导出", topology);

            byte[] value;
            int colNum = resultSet.getMetaData().getColumnCount();
            this.os = new ByteArrayOutputStream(colNum * 16);
            while (resultSet.next()) {

                for (int i = 1; i < colNum; i++) {
                    value = resultSet.getBytes(i);
                    writeFieldValue(os, value, i - 1);
                    // 附加分隔符
                    os.write(separator);
                }
                value = resultSet.getBytes(colNum);
                writeFieldValue(os, value, colNum - 1);
                if (isWithLastSep) {
                    // 附加分隔符
                    os.write(separator);
                }
                // 附加换行符
                os.write(FileUtil.SYS_NEW_LINE_BYTE);
                bufferedRowNum++;
                rowCount++;

                if (bufferedRowNum == GlobalVar.EMIT_BATCH_SIZE) {
                    rowCountStat.addAndGet(bufferedRowNum);
                    emitBatchData();
                    os.reset();
                    bufferedRowNum = 0;
                }
            }
            if (bufferedRowNum != 0) {
                rowCountStat.addAndGet(bufferedRowNum);
                // 最后剩余的元组
                dealWithRemainData();
                os.reset();
            }
            afterProduceData();
        } catch (SQLException | IOException e) {
            e.printStackTrace();
            logger.error("{} 导出发生错误: {}", topology, e.getMessage());
        } finally {
            IOUtil.close(os);
            CountStat.addDbRowCount(rowCount);
            logger.info("{} 导出行数：{}", topology, rowCount);
        }
    }

    protected void afterProduceData() {
    }

    protected abstract void emitBatchData();

    protected abstract void dealWithRemainData();

    protected abstract String getExportSql();

    /**
     * 根据引号模式来写入字段值
     * @param columnIdx 从 0 开始
     */
    protected void writeFieldValue(ByteArrayOutputStream os, byte[] value, int columnIdx) throws IOException {
        switch (quoteEncloseMode) {
        case NONE:
            FileUtil.writeToByteArrayStream(os, value);
            break;
        case FORCE:
            FileUtil.writeToByteArrayStreamWithQuote(os, value);
            break;
        case AUTO:
            if (columnDataMaskerList != null && columnDataMaskerList.get(columnIdx) != null) {
                value = columnDataMaskerList.get(columnIdx).doMask(value);
            }
            boolean isStringType = isStringTypeList.get(columnIdx);
            if (!isStringType) {
                FileUtil.writeToByteArrayStream(os, value);
            } else {
                // 检查是否有特殊字符
                boolean needQuote = FileUtil.containsSpecialBytes(value, specialCharList);

                if (needQuote) {
                    FileUtil.writeToByteArrayStreamWithQuote(os, value);
                } else {
                    FileUtil.writeToByteArrayStream(os, value);
                }
            }
            break;
        }
    }

    public void setCompressMode(CompressMode compressMode) {
        this.compressMode = compressMode;
    }

    public void putDataMaskerMap(Map<String, JSONObject> columnMaskerMap) {
        if (columnMaskerMap == null || columnMaskerMap.isEmpty()) {
            return;
        }
        if (this.columnDataMaskerList == null) {
            this.columnDataMaskerList = new ArrayList<>(Collections.nCopies(
                tableFieldMetaInfo.getFieldMetaInfoList().size(), null));
        }
        for (Map.Entry<String, JSONObject> columnMasker : columnMaskerMap.entrySet()) {
            AbstractDataMasker masker = DataMaskerFactory.getDataMasker(columnMasker.getValue());
            this.putDataMasker(columnMasker.getKey(), masker);
        }
    }

    private void putDataMasker(String columnName, AbstractDataMasker dataMasker) {
        List<FieldMetaInfo> fieldMetaInfoList = tableFieldMetaInfo.getFieldMetaInfoList();
        for (int i = 0; i < fieldMetaInfoList.size(); i++) {
            if (StringUtils.equalsIgnoreCase(columnName, fieldMetaInfoList.get(i).getName())) {
                this.columnDataMaskerList.set(i, dataMasker);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown mask column: " + columnName);
    }

    public void setWithLastSep(boolean withLastSep) {
        isWithLastSep = withLastSep;
    }
}
