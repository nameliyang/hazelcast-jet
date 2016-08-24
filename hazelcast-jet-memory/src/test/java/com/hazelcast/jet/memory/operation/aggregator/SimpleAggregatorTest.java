/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.memory.operation.aggregator;

import com.hazelcast.jet.io.SerializationOptimizer;
import com.hazelcast.jet.io.Pair;
import com.hazelcast.jet.memory.BaseMemoryTest;
import com.hazelcast.jet.memory.JetMemoryException;
import com.hazelcast.jet.memory.binarystorage.accumulator.Accumulator;
import com.hazelcast.jet.memory.binarystorage.accumulator.IntSumAccumulator;
import com.hazelcast.jet.memory.binarystorage.comparator.Comparator;
import com.hazelcast.jet.memory.binarystorage.comparator.LexicographicBitwiseComparator;
import com.hazelcast.jet.memory.binarystorage.comparator.StringComparator;
import com.hazelcast.jet.memory.memoryblock.MemoryChainingRule;
import com.hazelcast.jet.memory.memoryblock.MemoryContext;
import com.hazelcast.jet.memory.operation.OperationFactory;
import com.hazelcast.jet.memory.operation.aggregator.cursor.PairCursor;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class SimpleAggregatorTest extends BaseMemoryTest {
    private Aggregator aggregator;
    private final SerializationOptimizer optimizer = new SerializationOptimizer();


    @Override
    protected long heapSize() {
        return 1024L * 1024L * 1024L + 200 * 1024 * 1024;
    }

    @Override
    protected long blockSize() {
        return 128 * 1024;
    }

    @Before
    public void setUp() throws Exception {
        init();
    }

    @After
    public void tearDown() throws Exception {
        aggregator.dispose();
        cleanUp();
    }

    @Test
    public void testString2String() throws Exception {
        initAggregator(new StringComparator());
        int count = 10_000_000;
        byte[] markers = new byte[count];
        Arrays.fill(markers, (byte) 0);
        long t = System.currentTimeMillis();
        insertElements(1, count);
        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
        long start = System.currentTimeMillis();
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            final Pair<String, String> tt = (Pair<String, String>) cursor.asPair();
            markers[Integer.valueOf(tt.getKey()) - 1] = 1;
        }
        System.out.println("SelectionTime=" + (System.currentTimeMillis() - start));
        for (int i = 0; i < count; i++) {
            assertEquals(markers[i], 1);
        }
    }

    @Test
    public void testInt2Int() throws Exception {
        initAggregator(new LexicographicBitwiseComparator());

        int CNT = 1_000_000;
        byte[] markers = new byte[CNT];
        Pair<Integer, Integer> pair = new Pair<>();
        long t = System.currentTimeMillis();
        for (int i = 1; i <= CNT; i++) {
            pair.setKey(i);
            pair.setValue(i);
            aggregator.accept(pair);
        }

        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<Integer, Integer> tt = (Pair<Integer, Integer>) cursor.asPair();
            markers[tt.getKey() - 1] = 1;
        }

        for (int i = 0; i < CNT; i++) {
            assertEquals(markers[i], 1);
        }

        System.out.println("Time=" + (System.currentTimeMillis() - t));
    }

    @Test
    public void testString2StringMultiValue() throws Exception {
        initAggregator(new StringComparator());
        Pair<String, String> pair = new Pair<>();
        int KEYS_CNT = 100_000;
        int VALUES_CNT = 10;
        byte[] markers = new byte[KEYS_CNT];
        Arrays.fill(markers, (byte) 0);
        long t = System.currentTimeMillis();
        for (int i = 1; i <= 100_000; i++) {
            pair.setKey(String.valueOf(i));
            for (int ii = 0; ii < 10; ii++) {
                pair.setValue(String.valueOf(ii));
                aggregator.accept(pair);
            }
        }
        int iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, String> tt = (Pair<String, String>) cursor.asPair();
            markers[Integer.valueOf(tt.getKey()) - 1] = 1;
            iterations_count++;
        }

        assertEquals(iterations_count, KEYS_CNT * VALUES_CNT);

        for (int i = 0; i < KEYS_CNT; i++) {
            assertEquals(markers[i], 1);
        }

        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
    }

    @Test
    public void testString2StringAssociativeAccumulator() throws Exception {
        initAggregator(new StringComparator(), new IntSumAccumulator());
        Pair<String, Integer> pair = new Pair<>();

        int KEYS_CNT = 100_000;
        int VALUES_CNT = 10;
        byte[] markers = new byte[KEYS_CNT];
        Arrays.fill(markers, (byte) 0);
        long t = System.currentTimeMillis();
        for (int i = 1; i <= KEYS_CNT; i++) {
            pair.setKey(String.valueOf(i));
            for (int ii = 0; ii < VALUES_CNT; ii++) {
                pair.setValue(1);
                aggregator.accept(pair);
            }
        }
        int iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair<String, Integer>) cursor.asPair();
            markers[Integer.valueOf(tt.getKey()) - 1] = 1;
            iterations_count++;
            int v = tt.getValue();
            Assert.assertEquals(VALUES_CNT, v);
        }

        assertEquals(iterations_count, KEYS_CNT);

        for (int i = 0; i < KEYS_CNT; i++) {
            assertEquals(markers[i], 1);
        }

        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
    }


    @Test
    public void testString2StringNonAssociativeAccumulator() throws Exception {
        initAggregator(new StringComparator(), new NonAssociativeSumAccumulator());
        Pair<String, Integer> pair = new Pair<>();

        int KEYS_CNT = 100_000;
        int VALUES_CNT = 10;
        byte[] markers = new byte[KEYS_CNT];
        Arrays.fill(markers, (byte) 0);
        long t = System.currentTimeMillis();

        for (int i = 1; i <= KEYS_CNT; i++) {
            pair.setKey(String.valueOf(i));

            for (int ii = 0; ii < VALUES_CNT; ii++) {
                pair.setValue(1);
                aggregator.accept(pair); }
        }

        int iterations_count = 0;

        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair<String, Integer>) cursor.asPair();
            markers[Integer.valueOf(tt.getKey()) - 1] = 1;
            iterations_count++;
            int v = tt.getValue();
            Assert.assertEquals(VALUES_CNT, v);
        }

        assertEquals(iterations_count, KEYS_CNT);

        for (int i = 0; i < KEYS_CNT; i++) {
            assertEquals(markers[i], 1);
        }

        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
    }

    @Test
    public void testString2StringManyElements() throws Exception {
        initAggregator(new StringComparator());
        int CNT = 1_000_000;
        long t = System.currentTimeMillis();
        insertElements(1, CNT);
        insertElements(1, CNT);
        insertElements(1, CNT);
        long iterations_count = 0;
        String k = null;
        int localCNt = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            final Pair<String, String> tt = (Pair<String, String>) cursor.asPair();
            if (k == null) {
                k = tt.getKey();
            } else {
                localCNt++;
                Assert.assertEquals(k, tt.getKey());
                if (localCNt == 2) {
                    k = null;
                    localCNt = 0;
                }
            }
            iterations_count++;
        }

        assertEquals(iterations_count, 3 * CNT);
        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
    }


    @Test
    public void testString2StringManyElementsAndAccumulator() throws Exception {
        initAggregator(new StringComparator(), new IntSumAccumulator());
        int CNT = 3_000_000;
        long t = System.currentTimeMillis();
        insertIntElements(1, CNT);
        insertIntElements(1, CNT);
        insertIntElements(1, CNT);
        long iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair) cursor.asPair();
            Assert.assertEquals(3, (int) tt.getValue());
            iterations_count++;
        }
        assertEquals(iterations_count, CNT);
        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
    }

    @Test
    public void testString2StringManyElementsAndNonAssociativeAccumulator() throws Exception {
        initAggregator(new StringComparator(), new NonAssociativeSumAccumulator());
        int CNT = 3_000_000;
        long t = System.currentTimeMillis();
        insertIntElements(1, CNT);
        insertIntElements(1, CNT);
        insertIntElements(1, CNT);
        long iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair<String, Integer>) cursor.asPair();
            Assert.assertEquals(3, (int) tt.getValue());
            iterations_count++;
        }
        assertEquals(iterations_count, CNT);
        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
    }

    private void initAggregator(Comparator comparator) {
        initAggregator(comparator, null);
    }

    private void initAggregator(Comparator comparator, Accumulator accumulator) {
        memoryContext = new MemoryContext(heapMemoryPool, nativeMemoryPool, blockSize(), useBigEndian());
        aggregator = OperationFactory.getAggregator(
                memoryContext,
                optimizer,
                MemoryChainingRule.HEAP,
                1024,//partitionCount
                1024,//spillingBufferSize
                comparator,
                new Pair(),
                accumulator,
                "",
                1024,//spillingChunkSize
                false,
                true
        );
    }

    private void insertIntElements(int start, int elementsCount) throws Exception {
        final Pair<String, Integer> pair = new Pair<>();
        for (int i = start; i <= elementsCount; i++) {
            pair.setKey(String.valueOf(i));
            pair.setValue(1);
            aggregator.accept(pair);
        }
    }

    private void insertElements(int start, int elementsCount) throws Exception {
        final Pair<String, String> pair = new Pair<>();
        for (int i = start; i <= elementsCount; i++) {
            pair.setKey(String.valueOf(i));
            pair.setValue(String.valueOf(i));
            if (!aggregator.accept(pair)) {
                throw new JetMemoryException("Not enough memory (spilling is turned off)");
            }
        }
    }
}