/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.uring;

import io.netty.channel.unix.FileDescriptor;
import org.junit.Test;
import java.nio.charset.Charset;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import static org.junit.Assert.*;
import io.netty.buffer.ByteBuf;
import org.junit.experimental.theories.suppliers.TestedOn;

public class NativeTest {

    @Test
    public void canWriteFile() {
        final long eventId = 1;

        ByteBufAllocator allocator = new UnpooledByteBufAllocator(true);
        ByteBuf writeEventByteBuf = allocator.directBuffer(100);
        String inputString = "Hello World!";
        writeEventByteBuf.writeCharSequence(inputString, Charset.forName("UTF-8"));

        int fd = Native.createFile();

        RingBuffer ringBuffer = Native.createRingBuffer(32);
        IOUringSubmissionQueue submissionQueue = ringBuffer.getIoUringSubmissionQueue();
        IOUringCompletionQueue completionQueue = ringBuffer.getIoUringCompletionQueue();

        assertNotNull(ringBuffer);
        assertNotNull(submissionQueue);
        assertNotNull(completionQueue);

        assertTrue(submissionQueue.add(eventId, EventType.WRITE, fd, writeEventByteBuf.memoryAddress(),
        writeEventByteBuf.readerIndex(), writeEventByteBuf.writerIndex()));
        submissionQueue.submit();

        IOUringCqe ioUringCqe = completionQueue.ioUringWaitCqe();
        assertNotNull(ioUringCqe);
        assertEquals(inputString.length(), ioUringCqe.getRes());
        assertEquals(1, ioUringCqe.getEventId());
        writeEventByteBuf.release();

        ByteBuf readEventByteBuf = allocator.directBuffer(100);
        assertTrue(submissionQueue.add(eventId + 1, EventType.READ, fd, readEventByteBuf.memoryAddress(),
        readEventByteBuf.writerIndex(), readEventByteBuf.capacity()));
        submissionQueue.submit();

        ioUringCqe = completionQueue.ioUringWaitCqe();
        assertEquals(2, ioUringCqe.getEventId());
        assertEquals(inputString.length(), ioUringCqe.getRes());

        readEventByteBuf.writerIndex(ioUringCqe.getRes());
        byte[] dataRead = new byte[inputString.length()];
        readEventByteBuf.readBytes(dataRead);

        assertArrayEquals(inputString.getBytes(), dataRead);
        readEventByteBuf.release();
    }

    @Test
    public void timeoutTest() {

        RingBuffer ringBuffer = Native.createRingBuffer(32);
        IOUringSubmissionQueue submissionQueue = ringBuffer.getIoUringSubmissionQueue();
        final IOUringCompletionQueue completionQueue = ringBuffer.getIoUringCompletionQueue();

        assertNotNull(ringBuffer);
        assertNotNull(submissionQueue);
        assertNotNull(completionQueue);

        Thread thread = new Thread() {
            @Override
            public void run() {
                    final IOUringCqe ioUringCqe = completionQueue.ioUringWaitCqe();
                    assertEquals(-62, ioUringCqe.getRes());
                    assertEquals(1, ioUringCqe.getEventId());
            }
        };
        thread.start();
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        submissionQueue.addTimeout(0, 1);
        submissionQueue.submit();
    }

    //Todo clean
    @Test
    public void eventfdTest() {
        RingBuffer ringBuffer = Native.createRingBuffer(32);
        IOUringSubmissionQueue submissionQueue = ringBuffer.getIoUringSubmissionQueue();
        final IOUringCompletionQueue completionQueue = ringBuffer.getIoUringCompletionQueue();

        assertNotNull(ringBuffer);
        assertNotNull(submissionQueue);
        assertNotNull(completionQueue);

        final FileDescriptor eventFd = Native.newEventFd();
        assertTrue(submissionQueue.addPoll(1, eventFd.intValue(), EventType.POLL_EVENTFD));
        submissionQueue.submit();

        new Thread() {
            @Override
            public void run() {
                    Native.eventFdWrite(eventFd.intValue(), 1L);
            }
        }.start();

        IOUringCqe ioUringCqe = completionQueue.ioUringWaitCqe();
        assertEquals(1, ioUringCqe.getRes());
        assertEquals(1, ioUringCqe.getEventId());
    }

    //Todo clean
    //eventfd signal doesnt work when ioUringWaitCqe and eventFdWrite are executed in a thread
    //created this test to reproduce this "weird" bug
    @Test(timeout = 8000)
    public void eventfdNoSignal() throws InterruptedException {

        RingBuffer ringBuffer = Native.createRingBuffer(32);
        IOUringSubmissionQueue submissionQueue = ringBuffer.getIoUringSubmissionQueue();
        final IOUringCompletionQueue completionQueue = ringBuffer.getIoUringCompletionQueue();

        assertNotNull(ringBuffer);
        assertNotNull(submissionQueue);
        assertNotNull(completionQueue);

        Thread waitingCqe = new Thread() {
            @Override
            public void run() {
                IOUringCqe ioUringCqe = completionQueue.ioUringWaitCqe();
                assertEquals(1, ioUringCqe.getRes());
                assertEquals(1, ioUringCqe.getEventId());
            }
        };
        waitingCqe.start();
        final FileDescriptor eventFd = Native.newEventFd();
        assertTrue(submissionQueue.addPoll(1, eventFd.intValue(), EventType.POLL_EVENTFD));
        submissionQueue.submit();

        new Thread() {
            @Override
            public void run() {
                    Native.eventFdWrite(eventFd.intValue(), 1L);
            }
        }.start();

        waitingCqe.join();
    }
}
