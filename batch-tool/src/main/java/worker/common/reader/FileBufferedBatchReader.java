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

package worker.common.reader;

import com.lmax.disruptor.RingBuffer;
import model.ProducerExecutionContext;
import model.config.CompressMode;
import model.config.ConfigConstant;
import model.config.GlobalVar;
import model.stat.FileReaderStat;
import store.FileStorage;
import worker.common.BatchLineEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static model.config.GlobalVar.EMIT_BATCH_SIZE;

public abstract class FileBufferedBatchReader implements Runnable {

    protected final RingBuffer<BatchLineEvent> ringBuffer;
    protected final FileReaderStat fileReaderStat = new FileReaderStat();
    protected final CompressMode compressMode;
    protected final ProducerExecutionContext context;
    protected final List<File> fileList;
    protected int bufferedLineCount = 0;
    protected String[] lineBuffer;
    protected volatile int localProcessingFileIndex;
    protected long localProcessingBlockIndex = -1;
    /**
     * Block read
     */
    protected AtomicLong currentFileLineCount = new AtomicLong(0);

    protected FileStorage fileStorage;

    protected FileBufferedBatchReader(ProducerExecutionContext context,
                                      List<File> fileList,
                                      RingBuffer<BatchLineEvent> ringBuffer) {
        this(context, fileList, ringBuffer, CompressMode.NONE);
    }

    protected FileBufferedBatchReader(ProducerExecutionContext context,
                                      List<File> fileList,
                                      RingBuffer<BatchLineEvent> ringBuffer, CompressMode compressMode) {
        this.context = context;
        this.ringBuffer = ringBuffer;
        this.fileList = fileList;
        this.lineBuffer = new String[EMIT_BATCH_SIZE];
        this.compressMode = compressMode;
        GlobalVar.DEBUG_INFO.addFileReaderStat(fileReaderStat);
        this.fileStorage = context.getFileStorage();
    }

    public AtomicLong getCurrentFileLineCount() {
        return currentFileLineCount;
    }

    protected void appendToLineBuffer(String line) {
        lineBuffer[bufferedLineCount++] = line;
        if (bufferedLineCount == EMIT_BATCH_SIZE) {
            emitLineBuffer();
            lineBuffer = new String[EMIT_BATCH_SIZE];
            bufferedLineCount = 0;
        }
        fileReaderStat.increment();
        currentFileLineCount.incrementAndGet();
    }

    protected void emitLineBuffer() {
        long sequence = ringBuffer.next();
        BatchLineEvent event;
        try {
            event = ringBuffer.get(sequence);
            if (bufferedLineCount < EMIT_BATCH_SIZE) {
                // 插入结束标志
                lineBuffer[bufferedLineCount] = ConfigConstant.END_OF_BATCH_LINES;
            }
            event.setBatchLines(lineBuffer);
            event.setLocalProcessingFileIndex(localProcessingFileIndex);
            event.setLocalProcessingBlockIndex(localProcessingBlockIndex);
        } finally {
            beforePublish();
            ringBuffer.publish(sequence);
        }
    }

    @Override
    public void run() {
        try {
            fileReaderStat.start();
            init();
            readData();
        } catch (Exception e) {
            context.setException(e);
            throw e;
        } finally {
            afterRun();
        }
    }

    protected abstract void init();

    protected abstract void readData();

    private void afterRun() {
        context.getCountDownLatch().countDown();
        close();
    }

    protected abstract void close();

    protected abstract void beforePublish();

    public boolean useMagicSeparator() {
        return false;
    }

    public FileReaderStat getFileReaderStat() {
        return fileReaderStat;
    }

    protected File getLocalFile() {
        File file = fileList.get(localProcessingFileIndex);
        if (fileStorage != null) {
            // check file exists
            if (!file.exists() || !file.isFile()) {
                // download to local disk synchronously
                // should not reach here
//                fileStorage.get(file.getName(), file.getAbsolutePath());
                throw new IllegalStateException("File should be fetched already");
            }
        }
        return file;
    }
}
