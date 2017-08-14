/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.execution;

import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Processor;
import com.hazelcast.jet.Snapshottable;
import com.hazelcast.jet.Watermark;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.impl.execution.init.Contexts.ProcCtx;
import com.hazelcast.jet.impl.util.ArrayDequeInbox;
import com.hazelcast.jet.impl.util.CircularListCursor;
import com.hazelcast.jet.impl.util.OutboxImpl;
import com.hazelcast.jet.impl.util.ProgressState;
import com.hazelcast.jet.impl.util.ProgressTracker;
import com.hazelcast.spi.serialization.SerializationService;
import com.hazelcast.util.Preconditions;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.hazelcast.jet.impl.execution.DoneItem.DONE_ITEM;
import static com.hazelcast.jet.impl.execution.ProcessorState.COMPLETE;
import static com.hazelcast.jet.impl.execution.ProcessorState.END;
import static com.hazelcast.jet.impl.execution.ProcessorState.PROCESS_INBOX;
import static com.hazelcast.jet.impl.execution.ProcessorState.SAVE_SNAPSHOT;
import static com.hazelcast.jet.impl.execution.ProcessorState.EMIT_DONE_ITEM;
import static com.hazelcast.jet.impl.execution.ProcessorState.EMIT_BARRIER;
import static com.hazelcast.jet.impl.execution.ProcessorState.NULLARY_PROCESS;
import static com.hazelcast.jet.impl.util.ProgressState.DONE;
import static com.hazelcast.jet.impl.util.ProgressState.NO_PROGRESS;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;

public abstract class ProcessorTaskletBase implements Tasklet {

    private final ProgressTracker progTracker = new ProgressTracker();
    private final OutboundEdgeStream[] outstreams;
    private final OutboxImpl outbox;
    private final ProcCtx context;

    private final Processor processor;

    // casted #processor to Snapshottable or null, if #processor does not implement it
    private final Snapshottable snapshottable;
    private final SnapshotContext snapshotContext;
    private final BitSet barrierReceived; // indicates if current snapshot is received on the ordinal

    private final ArrayDequeInbox inbox = new ArrayDequeInbox(progTracker);
    private final Queue<ArrayList<InboundEdgeStream>> instreamGroupQueue;

    private CircularListCursor<InboundEdgeStream> instreamCursor;
    private InboundEdgeStream currInstream;
    private ProcessorState state = NULLARY_PROCESS;
    private long currSnapshot;


    ProcessorTaskletBase(ProcCtx context,
                         Processor processor,
                         List<InboundEdgeStream> instreams,
                         List<OutboundEdgeStream> outstreams,
                         SnapshotContext snapshotContext,
                         Queue<Object> snapshotQueue) {
        Preconditions.checkNotNull(processor, "processor");
        this.context = context;
        this.processor = processor;
        this.snapshottable = processor instanceof Snapshottable ? (Snapshottable) processor : null;
        this.instreamGroupQueue = instreams
                .stream()
                .collect(groupingBy(InboundEdgeStream::priority, TreeMap::new, toCollection(ArrayList::new)))
                .entrySet().stream()
                .map(Entry::getValue)
                .collect(toCollection(ArrayDeque::new));
        this.outstreams = outstreams.stream()
                                    .sorted(comparing(OutboundEdgeStream::ordinal))
                                    .toArray(OutboundEdgeStream[]::new);
        this.snapshotContext = snapshotContext;

        instreamCursor = popInstreamGroup();
        outbox = createOutbox(snapshotQueue);
        barrierReceived = new BitSet(instreams.size());
        state = instreams.size() == 0 ? COMPLETE : NULLARY_PROCESS;
    }

    private OutboxImpl createOutbox(Queue<Object> snapshotQueue) {
        Function<Object, ProgressState>[] functions = new Function[outstreams.length + (snapshotQueue == null ? 0 : 1)];
        for (int i = 0; i < outstreams.length; i++) {
            OutboundCollector collector = outstreams[i].getCollector();
            functions[i] = item -> item instanceof Watermark || item instanceof SnapshotBarrier || item == DONE_ITEM
                    ? collector.offerBroadcast(item) : collector.offer(item);
        }
        if (snapshotQueue != null) {
            functions[outstreams.length] = e -> snapshotQueue.offer(e) ? DONE : NO_PROGRESS;
        }

        return createOutboxInt(functions, snapshotQueue != null, progTracker,
                context.getEngine().getSerializationService());
    }

    protected abstract OutboxImpl createOutboxInt(Function<Object, ProgressState>[] outstreams, boolean hasSnapshot,
                                                  ProgressTracker progTracker, SerializationService serializationService);

    @Override @Nonnull
    public ProgressState call() {
        progTracker.reset();

        if (state == NULLARY_PROCESS) {
            if (processor.tryProcess()) {
                state = PROCESS_INBOX;
            } else {
                progTracker.notDone();
            }
        }

        if (state == PROCESS_INBOX) {
            if (inbox.isEmpty()) {
                tryFillInbox();
                if (barrierReceived.cardinality() == instreamGroupQueue.size())
                {}
            } else {
                progTracker.notDone();
            }

//            if (progTracker.isDone()) {
//                if (processor.complete()) {
//                    state = EMIT_DONE_ITEM;
//                } else {
//                    state = START_SNAPSHOT;
//                    progTracker.notDone();
//                }
//            } else {
//                if (!inbox.isEmpty()) {
//                    processor.process(currInstream.ordinal(), inbox);
//                }
//                if (inbox.isEmpty()) {
//                    state = START_SNAPSHOT;
//                }
//            }
        }

        if (state == SAVE_SNAPSHOT) {
            if (snapshottable.saveSnapshot()) {
                state = EMIT_BARRIER;
            } else {
                progTracker.notDone();
            }
        }

        if (state == EMIT_BARRIER) {
            if (outbox.offerToEdgesAndSnapshot(new SnapshotBarrier(currSnapshot))) {
                state = NULLARY_PROCESS;
            } else {
                progTracker.notDone();
            }
        }

//        if (state == START_SNAPSHOT) {
//            if (instreamCursor == null) {
//                // If our processor is now a source, check the flag in ExecutionContext to start a snapshot.
//                // Any processor becomes a source after its input completes.
//                requestedSnapshotId = snapshotContext.getCurrentSnapshotId();
//                assert requestedSnapshotId >= completedSnapshotId;
//            }
//            if (requestedSnapshotId == completedSnapshotId) {
//                // No new snapshot requested, skip snapshot creation
//                state = NULLARY_PROCESS;
//            } else if (snapshottable == null) {
//                // New snapshot requested, but our processor is stateless. Just forward the barrier.
//                state = EMIT_BARRIER;
//            } else if (outbox.offerToSnapshot(new SnapshotStartBarrier(requestedSnapshotId))) {
//                state = SAVE_SNAPSHOT;
//            } else {
//                progTracker.notDone();
//            }
//        }

        if (state == EMIT_DONE_ITEM) {
            if (outbox.offerToEdgesAndSnapshot(DONE_ITEM)) {
                state = END;
            } else {
                progTracker.notDone();
            }
        }





        return progTracker.toProgressState();
    }

    @Override
    public void init(CompletableFuture<Void> jobFuture) {
        context.initJobFuture(jobFuture);
        processor.init(outbox, context);
    }

    private void tryFillInbox() {
        if (instreamCursor == null) {
            return;
        }
        progTracker.notDone();
        final InboundEdgeStream first = instreamCursor.value();
        ProgressState result;
        do {
            currInstream = instreamCursor.value();
            result = NO_PROGRESS;

            // skip ordinals where a snapshot barrier has already been received
            if (snapshotContext.getGuarantee() == ProcessingGuarantee.EXACTLY_ONCE
                    && barrierReceived.get(currInstream.ordinal())) {
                continue;
            }
            result = currInstream.drainTo(inbox::add);
            progTracker.madeProgress(result.isMadeProgress());

            if (result.isDone()) {
                instreamCursor.remove();
            }

            // do not drain any more items after receiving a snapshot barrier
            Object last = inbox.peekLast();
            if (last instanceof SnapshotBarrier) {
                observeSnapshot(currInstream.ordinal(), ((SnapshotBarrier)last).snapshotId());
                return;
            }

            // pop current priority qroup
            if (!instreamCursor.advance()) {
                instreamCursor = popInstreamGroup();
                return;
            }
        } while (!result.isMadeProgress() && instreamCursor.value() != first);
    }

    private CircularListCursor<InboundEdgeStream> popInstreamGroup() {
        return Optional.ofNullable(instreamGroupQueue.poll())
                       .map(CircularListCursor::new)
                       .orElse(null);
    }

    protected OutboxImpl getOutbox() {
        return outbox;
    }

    @Override
    public String toString() {
        return "ProcessorTasklet{vertex=" + context.vertexName() + ", processor=" + processor + '}';
    }

    private void observeSnapshot(int ordinal, long snapshotId) {
        if (snapshotId != currSnapshot) {
            throw new JetException("Unexpected snapshot barrier " + snapshotId + " from ordinal " + ordinal +
                    " expected " + currSnapshot);
        }
        barrierReceived.set(ordinal);
    }

}

