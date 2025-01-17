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

package model.config;

import store.FileStorage;

import java.nio.charset.Charset;

/**
 * 可自定义且有默认参数的配置项
 * 导入导出通用配置
 */
public class BaseConfig {

    private final FileMode fileMode = new FileMode();
    /**
     * 分隔符
     */
    protected String separator = ConfigConstant.DEFAULT_SEPARATOR;
    /**
     * 指定字符集
     */
    protected Charset charset = ConfigConstant.DEFAULT_CHARSET;

    /**
     * 第一行是否为字段名
     */
    protected boolean isWithHeader;

    protected boolean shardingEnabled;

    protected DdlMode ddlMode = DdlMode.NO_DDL;

    protected boolean dropTableIfExists = false;

    protected CompressMode compressMode = CompressMode.NONE;

    protected EncryptionConfig encryptionConfig = EncryptionConfig.NONE;

    protected FileFormat fileFormat = FileFormat.NONE;

    /**
     * 引号模式
     */
    protected QuoteEncloseMode quoteEncloseMode;
    private boolean isWithLastSep = false;
    private boolean isWithView = false;
    private int startPart = -1;
    private int endPart = -1;

    /**
     * null means LOCAL
     */
    protected FileStorage fileStorage = null;

    public BaseConfig(boolean shardingEnabled) {
        this.shardingEnabled = shardingEnabled;
    }

    public int getStartPart() {
        return startPart;
    }

    public void setStartPart(int startPart) {
        this.startPart = startPart;
    }

    public int getEndPart() {
        return endPart;
    }

    public void setEndPart(int endPart) {
        this.endPart = endPart;
    }

    public String getSeparator() {
        return separator;
    }

    public void setSeparator(String separator) {
        if (separator.isEmpty()) {
            throw new IllegalArgumentException("Separator cannot be empty");
        }
        // 分隔符不能包含特殊字符
        for (String illegalStr : ConfigConstant.ILLEGAL_SEPARATORS) {
            if (separator.contains(illegalStr)) {
                throw new IllegalArgumentException("Illegal separator: " + separator);
            }
        }
        this.separator = separator;
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public boolean isWithHeader() {
        return isWithHeader;
    }

    public void setWithHeader(boolean withHeader) {
        isWithHeader = withHeader;
    }

    public boolean isShardingEnabled() {
        return shardingEnabled;
    }

    public QuoteEncloseMode getQuoteEncloseMode() {
        return quoteEncloseMode;
    }

    public void setQuoteEncloseMode(QuoteEncloseMode quoteEncloseMode) {
        this.quoteEncloseMode = quoteEncloseMode;
    }

    public DdlMode getDdlMode() {
        return ddlMode;
    }

    public void setDdlMode(DdlMode ddlMode) {
        this.ddlMode = ddlMode;
    }

    public CompressMode getCompressMode() {
        return compressMode;
    }

    public void setCompressMode(CompressMode compressMode) {
        this.compressMode = compressMode;
        if (compressMode != CompressMode.NONE) {
            fileMode.setCompress();
        }
    }

    public EncryptionConfig getEncryptionConfig() {
        return encryptionConfig;
    }

    public void setEncryptionConfig(EncryptionConfig encryptionConfig) {
        this.encryptionConfig = encryptionConfig;
        if (!encryptionConfig.equals(EncryptionConfig.NONE)) {
            fileMode.setEncryption();
        }
    }

    public FileFormat getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(FileFormat fileFormat) {
        this.fileFormat = fileFormat;
        if (fileFormat != FileFormat.NONE) {
            fileMode.setFileFormat();
        }
    }

    public boolean isWithLastSep() {
        return isWithLastSep;
    }

    public void setWithLastSep(boolean withLastSep) {
        isWithLastSep = withLastSep;
    }

    public boolean isWithView() {
        return isWithView;
    }

    public void setWithView(boolean withView) {
        isWithView = withView;
    }

    /**
     * 目前 压缩模式、加密、特殊文件格式三者配置互不兼容
     */
    public void validate() {
        if (fileMode.bitCount() > 1) {
            throw new IllegalArgumentException(String.format(
                "Please check compression/encryption/file-format config: %s/%s/%s",
                compressMode, encryptionConfig, fileFormat));
        }
    }

    public boolean isDropTableIfExists() {
        return dropTableIfExists;
    }

    public void setDropTableIfExists(boolean dropTableIfExists) {
        this.dropTableIfExists = dropTableIfExists;
    }

    public FileStorage getFileStorage() {
        return fileStorage;
    }

    public void setFileStorage(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public String toString() {
        return "BaseConfig{" +
            "separator='" + separator + '\'' +
            ", charset='" + charset + '\'' +
            ", isWithHeader='" + isWithHeader + '\'' +
            ", isWithLastSep='" + isWithLastSep + '\'' +
            ", isWithView='" + isWithView + '\'' +
            ", quoteEncloseMode='" + quoteEncloseMode + '\'' +
            ", compressMode='" + compressMode + '\'' +
            ", ddlMode='" + ddlMode + '\'' +
            ", dropTableIfExists='" + dropTableIfExists + '\'' +
            ", encryptionConfig='" + encryptionConfig + '\'' +
            '}';
    }

    /**
     * consider a better design
     */
    public void close() {
        if (fileStorage != null) {
            fileStorage.close();
        }
    }

    private static class FileMode {
        static final byte COMPRESS_FLAG = 1;
        static final byte ENCRYPTION_FLAG = 1 << 2;
        static final byte FILE_FORMAT_FLAG = 1 << 3;

        byte flag = 0;

        void setCompress() {
            this.flag |= COMPRESS_FLAG;
        }

        void setEncryption() {
            this.flag |= ENCRYPTION_FLAG;
        }

        void setFileFormat() {
            this.flag |= FILE_FORMAT_FLAG;
        }

        int bitCount() {
            int count = 0;
            byte n = flag;
            while (n != 0) {
                n &= n - 1;
                count++;
            }
            return count;
        }
    }
}
