/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package worker.common.reader;

import com.lmax.disruptor.RingBuffer;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;
import model.ProducerExecutionContext;
import model.config.ConfigConstant;
import model.config.GlobalVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.IOUtil;
import worker.common.BatchLineEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class CsvReader extends FileBufferedBatchReader {
    private static final Logger logger = LoggerFactory.getLogger(CsvReader.class);

    private CSVReader reader;

    public CsvReader(ProducerExecutionContext context,
                     List<File> fileList, int fileIndex,
                     RingBuffer<BatchLineEvent> ringBuffer) {
        super(context, fileList, ringBuffer);

        this.localProcessingFileIndex = fileIndex;
    }

    @Override
    protected void init() {
        String sep = context.getSeparator();
        if (sep.length() != 1) {
            throw new IllegalArgumentException("CSV reader only support one-char separator");
        }
        char sepChar = sep.charAt(0);
        RFC4180Parser parser = new RFC4180ParserBuilder().withSeparator(sepChar).build();
        try {
            this.reader = new CSVReaderBuilder(new InputStreamReader(
                new FileInputStream(getLocalFile().getAbsolutePath()), context.getCharset()))
                .withCSVParser(parser).withMultilineLimit(GlobalVar.MAX_CSV_MULTI_LINE).build();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    protected void readData() {
        try {
            if (context.isWithHeader()) {
                // skip header
                reader.readNext();
            }
            for (String[] fields; (fields = reader.readNext()) != null; ) {
                localProcessingBlockIndex++;
                String line = String.join(ConfigConstant.MAGIC_CSV_SEP1, fields);
                appendToLineBuffer(line);
            }
            emitLineBuffer();
            logger.info("{} 读取完毕，读取行数：{}", getLocalFile().getPath(),
                currentFileLineCount.get());
        } catch (IOException e) {
            logger.error(e.getMessage());
        } catch (CsvValidationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void close() {
        IOUtil.close(reader);
    }

    @Override
    protected void beforePublish() {
        context.getEmittedDataCounter().getAndIncrement();
    }

    @Override
    public boolean useMagicSeparator() {
        return true;
    }
}

