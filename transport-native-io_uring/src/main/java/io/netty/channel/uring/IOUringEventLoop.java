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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.collection.LongObjectHashMap;

import java.util.concurrent.Executor;

import static io.netty.channel.unix.Errors.*;

class IOUringEventLoop extends SingleThreadEventLoop {

    // events should be unique to identify which event type that was
    private long eventIdCounter;
    private final LongObjectHashMap<Event> events = new LongObjectHashMap<Event>();
    private RingBuffer ringBuffer;

    protected IOUringEventLoop(final EventLoopGroup parent, final Executor executor, final boolean addTaskWakesUp) {
        super(parent, executor, addTaskWakesUp);
        ringBuffer = Native.createRingBuffer(32);
    }

    public long incrementEventIdCounter() {
        long eventId = eventIdCounter;
        System.out.println(" incrementEventIdCounter EventId: " + eventId);
        eventIdCounter++;
        return eventId;
    }

    public void addNewEvent(Event event) {
        events.put(event.getId(), event);
    }

    @Override
    protected void run() {
        for (; ; ) {
            final IOUringCompletionQueue ioUringCompletionQueue = ringBuffer.getIoUringCompletionQueue();
            final IOUringCqe ioUringCqe = ioUringCompletionQueue.peek(); // or waiting

            if (ioUringCqe != null) {
                final Event event = events.get(ioUringCqe.getEventId());
                System.out.println("EventId: " + ioUringCqe.getEventId());

                if (event != null) {
                    switch (event.getOp()) {
                    case ACCEPT:
                        System.out.println("Accept Res: " + ioUringCqe.getRes());
                        if (ioUringCqe.getRes() != -1 && ioUringCqe.getRes() != ERRNO_EAGAIN_NEGATIVE &&
                            ioUringCqe.getRes() != ERRNO_EWOULDBLOCK_NEGATIVE) {
                            AbstractIOUringServerChannel abstractIOUringServerChannel =
                                    (AbstractIOUringServerChannel) event.getAbstractIOUringChannel();
                            final IOUringRecvByteAllocatorHandle allocHandle =
                                    (IOUringRecvByteAllocatorHandle) event.getAbstractIOUringChannel().unsafe()
                                                                          .recvBufAllocHandle();
                            final ChannelPipeline pipeline = event.getAbstractIOUringChannel().pipeline();

                            allocHandle.incMessagesRead(1);
                            try {
                                pipeline.fireChannelRead(abstractIOUringServerChannel
                                                                 .newChildChannel(
                                                                         abstractIOUringServerChannel.getChannel()
                                                                                                     .getSocket()
                                                                                                     .getFd(),
                                                                         ringBuffer.getIoUringSubmissionQueue()));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            allocHandle.readComplete();
                            pipeline.fireChannelReadComplete();
                        }
                        long eventId = incrementEventIdCounter();
                        event.setId(eventId);
                        ringBuffer.getIoUringSubmissionQueue()
                                  .add(eventId, EventType.ACCEPT, event.getAbstractIOUringChannel().getSocket().getFd(),
                                       0,
                                       0,
                                       0);
                        addNewEvent(event);
                        ringBuffer.getIoUringSubmissionQueue().submit();
                        break;
                    case READ:
                        ByteBuf byteBuf = event.getReadBuffer();
                        int localReadAmount = ioUringCqe.getRes();
                        if (localReadAmount > 0) {
                            byteBuf.writerIndex(byteBuf.writerIndex() + localReadAmount);
                        }

                        final IOUringRecvByteAllocatorHandle allocHandle =
                                (IOUringRecvByteAllocatorHandle) event.getAbstractIOUringChannel().unsafe()
                                                                      .recvBufAllocHandle();
                        final ChannelPipeline pipeline = event.getAbstractIOUringChannel().pipeline();

                        allocHandle.lastBytesRead(localReadAmount);
                        if (allocHandle.lastBytesRead() <= 0) {
                            // nothing was read, release the buffer.
                            byteBuf.release();
                            byteBuf = null;
                            break;
                        }

                        allocHandle.incMessagesRead(1);
                        //readPending = false;
                        pipeline.fireChannelRead(byteBuf);
                        byteBuf = null;
                        allocHandle.readComplete();
                        pipeline.fireChannelReadComplete();

                        break;
                    case WRITE:
                        //remove bytes
                        int localFlushAmount = ioUringCqe.getRes();
                        if (localFlushAmount > 0) {
                            event.getAbstractIOUringChannel().unsafe().outboundBuffer().removeBytes(localFlushAmount);
                        }
                        break;
                    }
                } else {
                    System.out.println("Event is null!!!! ");
                }
            }
            //run tasks
            if (hasTasks()) {
                runAllTasks();
            }
        }
    }

    public RingBuffer getRingBuffer() {
        return ringBuffer;
    }
}
