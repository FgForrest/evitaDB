/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.core.cdc.ChangeCaptureRingBuffer.OutsideScopeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link ChangeCaptureRingBuffer} class.
 * Tests all cases of wraparound ring buffer behavior and verifies that the copyData methods
 * always return the correct count and order of items.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Ring buffer should")
class ChangeCaptureRingBufferTest {

    public static final BiConsumer<Integer, ChangeCatalogCapture> DEFAULT_ASSERTIONS = (i, capture) -> {
        assertEquals(1L, capture.version());
        assertEquals(i, capture.index());
        assertEquals("entity" + i, capture.entityType());
    };

    /**
     * Tests a half-empty buffer scenario where the buffer is not yet full.
     */
    @Test
    @DisplayName("handle half-empty buffer scenario")
    void shouldHandleHalfEmptyBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(
            1L, 0, 10L, 5, ChangeCatalogCapture.class
        );

        // Add 3 captures to the buffer (less than capacity)
        final ChangeCatalogCapture capture1 = createCapture(1L, 0, "entity1");
        final ChangeCatalogCapture capture2 = createCapture(1L, 1, "entity1");
        final ChangeCatalogCapture capture3 = createCapture(1L, 2, "entity2");

        buffer.offer(capture1);
        buffer.offer(capture2);
        buffer.offer(capture3);

        // Verify buffer state
        assertEquals(1L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Copy data from the buffer starting from version 1, index 0
        final List<ChangeCatalogCapture> captureList = List.of(capture1, capture2, capture3);
        verifyCopyToOperationWithList(buffer, 1L, 0, 3, captureList);
    }

    /**
     * Tests a buffer that wraps but is not yet full.
     */
    @Test
    @DisplayName("handle wrapping but not full buffer scenario")
    void shouldHandleWrappingButNotFullBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(
            1L, 0, 10L, 5, ChangeCatalogCapture.class
        );

        // Add 7 captures to the buffer (more than capacity, causing wrap)
        for (int i = 0; i < 7; i++) {
            buffer.offer(createCapture(1L, i, "entity" + i));
        }

        // Verify buffer state - the first two items should be removed
        assertEquals(1L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(2, buffer.getEffectiveStartIndex()); // second index (we're indexing from zero)

        // Copy data from the buffer starting from version 1, index 2
        verifyCopyToOperation(buffer, 1L, 2, 5, DEFAULT_ASSERTIONS, 2, 7);

        // Copy data from the buffer starting from version 1, index 4
        verifyCopyToOperation(buffer, 1L, 4, 3, DEFAULT_ASSERTIONS, 4, 7);

	    // Try to copy from an index that's no longer in the buffer
        try {
            buffer.copyTo(new WalPointer(1L, 0), new ArrayDeque<>());
            fail("Should throw OutsideScopeException");
        } catch (OutsideScopeException e) {
            // Expected exception
        }
    }

    /**
     * Tests a buffer that wraps and is completely full.
     */
    @Test
    @DisplayName("handle wrapping and full buffer scenario")
    void shouldHandleWrappingAndFullBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add exactly 5 captures to fill the buffer
        for (int i = 0; i < 5; i++) {
            buffer.offer(createCapture(1L, i, "entity" + i));
        }

        // Verify buffer state
        assertEquals(1L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Add one more capture to cause the buffer to wrap and remove the oldest item
        buffer.offer(createCapture(1L, 5, "entity5"));

        // Verify buffer state - the first item should be removed
        assertEquals(1L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(1, buffer.getEffectiveStartIndex());

        // Copy data from the buffer starting from version 1, index 1
        verifyCopyToOperation(buffer, 1L, 1, 5, DEFAULT_ASSERTIONS, 1, 6);
    }

    /**
     * Tests the copyTo method to verify it returns the correct count and order of items.
     */
    @Test
    @DisplayName("copy data with correct count and order")
    void shouldCopyDataWithCorrectCountAndOrder() {
        // Create a buffer with size 10
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 10, ChangeCatalogCapture.class);

        // Add captures with different versions and indexes
        buffer.offer(createCapture(1L, 0, "entity1"));
        buffer.offer(createCapture(1L, 1, "entity1"));
        buffer.offer(createCapture(2L, 0, "entity2"));
        buffer.offer(createCapture(2L, 1, "entity2"));
        buffer.offer(createCapture(3L, 0, "entity3"));

        // Copy data from version 1, index 1
        final List<ChangeCatalogCapture> expectedCaptures1 = new ArrayList<>();
        expectedCaptures1.add(createCapture(1L, 1, "entity1"));
        expectedCaptures1.add(createCapture(2L, 0, "entity2"));
        expectedCaptures1.add(createCapture(2L, 1, "entity2"));
        expectedCaptures1.add(createCapture(3L, 0, "entity3"));
        verifyCopyToOperationWithList(buffer, 1L, 1, 4, expectedCaptures1);

        // Copy data from version 2, index 0
        final List<ChangeCatalogCapture> expectedCaptures2 = new ArrayList<>();
        expectedCaptures2.add(createCapture(2L, 0, "entity2"));
        expectedCaptures2.add(createCapture(2L, 1, "entity2"));
        expectedCaptures2.add(createCapture(3L, 0, "entity3"));
        verifyCopyToOperationWithList(buffer, 2L, 0, 3, expectedCaptures2);
    }

    /**
     * Tests the effectiveLastCatalogVersion property to ensure it limits the items returned.
     */
    @Test
    @DisplayName("respect effective last catalog version")
    void shouldRespectEffectiveLastCatalogVersion() {
        // Create a buffer with size 10 and effectiveLastCatalogVersion of 2
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 2L, 10, ChangeCatalogCapture.class);

        // Add captures with different versions
        buffer.offer(createCapture(1L, 0, "entity1"));
        buffer.offer(createCapture(1L, 1, "entity1"));
        buffer.offer(createCapture(2L, 0, "entity2"));
        buffer.offer(createCapture(2L, 1, "entity2"));
        buffer.offer(createCapture(3L, 0, "entity3")); // This should not be returned due to effectiveLastCatalogVersion

        // Copy data from version 1, index 0
        verifyCopyToOperation(buffer, 1L, 0, 4, (i, capture) -> {
            if (i < 2) {
                assertEquals(1L, capture.version());
            } else {
                assertEquals(2L, capture.version());
                i -= 2;
            }
            assertEquals(i, capture.index());
        }, 0, 4);

        // Update effectiveLastCatalogVersion to include version 3
        buffer.setEffectiveLastCatalogVersion(3L);

        // Copy data again and verify it returns 5 items now
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(1L, 0), result);
            assertEquals(5, result.size()); // Now should return all 5 items
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Helper method to create a ChangeCatalogCapture instance.
     */
	@Nonnull
    private static ChangeCatalogCapture createCapture(long version, int index, String entityType) {
        return new ChangeCatalogCapture(
            version,
            index,
            CaptureArea.DATA,
            entityType,
            null,
            Operation.UPSERT,
            null
        );
    }

    /**
     * Helper method to verify the buffer copy operation with expected captures.
     *
     * @param buffer           the buffer to copy from
     * @param version          the version to start copying from
     * @param index            the index to start copying from
     * @param expectedSize     the expected number of items to be copied
     * @param expectedCaptures the list of expected captures
     */
    private static void verifyCopyToOperationWithList(
        @Nonnull ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer,
        long version,
        int index,
        int expectedSize,
        @Nonnull List<ChangeCatalogCapture> expectedCaptures
    ) {
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(version, index), result);
            assertEquals(expectedSize, result.size());

            // Verify the order of items
            for (ChangeCatalogCapture expectedCapture : expectedCaptures) {
                assertEquals(expectedCapture, result.poll());
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Helper method to verify the buffer copy operation with validation function.
     *
     * @param buffer         the buffer to copy from
     * @param version        the version to start copying from
     * @param index          the index to start copying from
     * @param expectedSize   the expected number of items to be copied
     * @param validator      the BiConsumer to validate each capture against its index
     * @param startIndex     the start index for validation
     * @param endIndexExcl   the end index (exclusive) for validation
     */
    /**
     * Generates a stream of 20 random seeds for fuzzy testing.
     */
    @Nonnull
    static Stream<Arguments> generateRandomSeeds() {
        final Random random = new Random();
        return IntStream.generate(() -> random.nextInt(1000))
            .distinct()
            .limit(20)
            .mapToObj(Arguments::of);
    }

    /**
     * Helper method to verify the buffer copy operation.
     *
     * @param buffer     the buffer to copy from (must not be null)
     * @param version    the version to start copying from
     * @param index      the index to start copying from
     * @param expectedSize the expected number of items to be copied
     * @param validator  callback to validate each captured change (must not be null)
     * @param startIndex start index (inclusive) in expected sequence
     * @param endIndexExcl end index (exclusive) in expected sequence
     */
    private static void verifyCopyToOperation(
        @Nonnull ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer,
        long version,
        int index,
        int expectedSize,
        @Nonnull BiConsumer<Integer, ChangeCatalogCapture> validator,
        int startIndex,
        int endIndexExcl
    ) {
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(version, index), result);
            assertEquals(expectedSize, result.size());

            // Verify the order of items
            for (int i = startIndex; i < endIndexExcl; i++) {
                ChangeCatalogCapture capture = result.poll();
                validator.accept(i, capture);
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }
    /**
     * Tests the clearAllUntil method on an empty buffer.
     * Verifies that calling clearAllUntil on an empty buffer doesn't cause any issues.
     */
    @Test
    @DisplayName("handle clearAllUntil on empty buffer")
    void shouldHandleClearAllUntilOnEmptyBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Call clearAllUntil on the empty buffer
        buffer.clearAllUntil(2L);

        // Verify buffer state
        assertEquals(2L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Add a capture and verify it's still accessible
        final ChangeCatalogCapture capture = createCapture(3L, 0, "entity0");
        buffer.offer(capture);

        // Copy data from the buffer and verify
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(2L, 0), result);
            assertEquals(1, result.size());
            assertEquals(capture, result.poll());
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllUntil method on a partially filled buffer.
     * Verifies that items with versions less than the specified version are cleared.
     */
    @Test
    @DisplayName("handle clearAllUntil on partially filled buffer")
    void shouldHandleClearAllUntilOnPartiallyFilledBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add captures with different versions
        buffer.offer(createCapture(1L, 0, "entity0"));
        buffer.offer(createCapture(2L, 0, "entity1"));
        buffer.offer(createCapture(3L, 0, "entity2"));

        // Clear all entries up to version 2
        buffer.clearAllUntil(2L);

        // Verify buffer state
        assertEquals(2L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Copy data from the buffer and verify only version 3 remains
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(2L, 0), result);
            assertEquals(2, result.size());
            final ChangeCatalogCapture capture1 = result.poll();
            assertEquals(2L, capture1.version());
            assertEquals(0, capture1.index());
            assertEquals("entity1", capture1.entityType());

            final ChangeCatalogCapture capture2 = result.poll();
            assertEquals(3L, capture2.version());
            assertEquals(0, capture2.index());
            assertEquals("entity2", capture2.entityType());
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }

        // Try to access data before the cleared version
        try {
            buffer.copyTo(new WalPointer(0L, 0), new ArrayDeque<>());
            fail("Should throw OutsideScopeException");
        } catch (OutsideScopeException e) {
            // Expected exception
        }
    }

    /**
     * Tests the clearAllUntil method on a full buffer.
     * Verifies that items with versions less than the specified version are cleared.
     */
    @Test
    @DisplayName("handle clearAllUntil on full buffer")
    void shouldHandleClearAllUntilOnFullBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add exactly 5 captures to fill the buffer
        for (int i = 0; i < 5; i++) {
            buffer.offer(createCapture(i + 1L, 0, "entity" + i));
        }

        // Clear all entries up to version 3
        buffer.clearAllUntil(3L);

        // Verify buffer state
        assertEquals(3L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Copy data from the buffer and verify only versions 3, 4, and 5 remain
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(3L, 0), result);
            assertEquals(3, result.size());

            // Verify the remaining captures
            for (int i = 0; i < 3; i++) {
                final ChangeCatalogCapture capture = result.poll();
                assertEquals(i + 3L, capture.version());
                assertEquals(0, capture.index());
                assertEquals("entity" + (i + 2), capture.entityType());
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllUntil method on a wrapped around buffer.
     * Verifies that items with versions less than the specified version are cleared
     * correctly when the buffer has wrapped around.
     */
    @Test
    @DisplayName("handle clearAllUntil on wrapped around buffer")
    void shouldHandleClearAllUntilOnWrappedAroundBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add 7 captures to the buffer (more than capacity, causing wrap)
        for (int i = 0; i < 7; i++) {
            buffer.offer(createCapture(i + 1L, 0, "entity" + i));
        }

        // Verify buffer has wrapped around
        assertEquals(3, buffer.getEffectiveStartCatalogVersion());

        // Clear all entries up to version 4
        buffer.clearAllUntil(4L);

        // Verify buffer state
        assertEquals(4L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Copy data from the buffer and verify only versions 4, 5, 6, and 7 remain
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(4L, 0), result);
            assertEquals(4, result.size());

            // Verify the remaining captures
            for (int i = 0; i < 4; i++) {
                final ChangeCatalogCapture capture = result.poll();
                assertEquals(i + 4L, capture.version());
                assertEquals(0, capture.index());
                assertEquals("entity" + (i + 3), capture.entityType());
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllAfter method on an empty buffer.
     * Verifies that calling clearAllAfter on an empty buffer doesn't cause any issues.
     */
    @Test
    @DisplayName("handle clearAllAfter on empty buffer")
    void shouldHandleClearAllAfterOnEmptyBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Call clearAllAfter on the empty buffer
        buffer.clearAllAfter(2L);

        // Verify buffer state
        assertEquals(1L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Add a capture and verify it's still accessible
        final ChangeCatalogCapture capture = createCapture(2L, 0, "entity0");
        buffer.offer(capture);

        // Copy data from the buffer and verify
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(1L, 0), result);
            assertEquals(1, result.size());
            assertEquals(capture, result.poll());
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllAfter method on a partially filled buffer.
     * Verifies that items with versions greater than the specified version are cleared.
     */
    @Test
    @DisplayName("handle clearAllAfter on partially filled buffer")
    void shouldHandleClearAllAfterOnPartiallyFilledBuffer() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add captures with different versions
        buffer.offer(createCapture(1L, 0, "entity0"));
        buffer.offer(createCapture(2L, 0, "entity1"));
        buffer.offer(createCapture(3L, 0, "entity2"));

        // Clear all entries after version 2
        buffer.clearAllAfter(2L);

        // Verify buffer state
        assertEquals(1L, buffer.getEffectiveStartCatalogVersion());
        assertEquals(0, buffer.getEffectiveStartIndex());

        // Copy data from the buffer and verify only versions 1 and 2 remain
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(1L, 0), result);
            assertEquals(2, result.size());

            final ChangeCatalogCapture capture1 = result.poll();
            assertEquals(1L, capture1.version());
            assertEquals(0, capture1.index());
            assertEquals("entity0", capture1.entityType());

            final ChangeCatalogCapture capture2 = result.poll();
            assertEquals(2L, capture2.version());
            assertEquals(0, capture2.index());
            assertEquals("entity1", capture2.entityType());
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllAfter method on a full buffer that has wrapped around.
     * Verifies that items with versions greater than the specified version are cleared
     * correctly when the buffer has wrapped around.
     */
    @Test
    @DisplayName("handle clearAllAfter on wrapped around buffer at the boundary")
    void shouldHandleClearAllAfterOnWrappedAroundBufferAtTheBoundary() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add 7 captures to the buffer (more than capacity, causing wrap)
        for (int i = 0; i < 7; i++) {
            buffer.offer(createCapture(i + 1L, 0, "entity" + i));
        }

        // Verify buffer has wrapped around
        assertEquals(3, buffer.getEffectiveStartCatalogVersion());

        // Clear all entries after version 5
        buffer.clearAllAfter(5L);

        // Copy data from the buffer and verify only versions 3, 4, and 5 remain
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(3L, 0), result);
            assertEquals(3, result.size());

            // Verify the remaining captures
            for (int i = 0; i < 3; i++) {
                final ChangeCatalogCapture capture = result.poll();
                assertEquals(i + 3L, capture.version());
                assertEquals(0, capture.index());
                assertEquals("entity" + (i + 2), capture.entityType());
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllAfter method on a full buffer that has wrapped around.
     * Verifies that items with versions greater than the specified version are cleared
     * correctly when the buffer has wrapped around.
     */
    @Test
    @DisplayName("handle clearAllAfter on wrapped around buffer at tail")
    void shouldHandleClearAllAfterOnWrappedAroundBufferAtTail() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add 7 captures to the buffer (more than capacity, causing wrap)
        for (int i = 0; i < 7; i++) {
            buffer.offer(createCapture(i + 1L, 0, "entity" + i));
        }

        // Verify buffer has wrapped around
        assertEquals(3, buffer.getEffectiveStartCatalogVersion());

        // Clear all entries after version 4
        buffer.clearAllAfter(4L);

        // Copy data from the buffer and verify only versions 3, 4, and 5 remain
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(3L, 0), result);
            assertEquals(2, result.size());

            // Verify the remaining captures
            for (int i = 0; i < 2; i++) {
                final ChangeCatalogCapture capture = result.poll();
                assertEquals(i + 3L, capture.version());
                assertEquals(0, capture.index());
                assertEquals("entity" + (i + 2), capture.entityType());
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Tests the clearAllAfter method on a full buffer that has wrapped around.
     * Verifies that items with versions greater than the specified version are cleared
     * correctly when the buffer has wrapped around.
     */
    @Test
    @DisplayName("handle clearAllAfter on wrapped around buffer at head")
    void shouldHandleClearAllAfterOnWrappedAroundBufferAtHead() {
        // Create a buffer with size 5
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, 5, ChangeCatalogCapture.class);

        // Add 7 captures to the buffer (more than capacity, causing wrap)
        for (int i = 0; i < 7; i++) {
            buffer.offer(createCapture(i + 1L, 0, "entity" + i));
        }

        // Verify buffer has wrapped around
        assertEquals(3, buffer.getEffectiveStartCatalogVersion());

        // Clear all entries after version 5
        buffer.clearAllAfter(6L);

        // Copy data from the buffer and verify only versions 3, 4, and 5 remain
        final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();
        try {
            buffer.copyTo(new WalPointer(3L, 0), result);
            assertEquals(4, result.size());

            // Verify the remaining captures
            for (int i = 0; i < 4; i++) {
                final ChangeCatalogCapture capture = result.poll();
                assertEquals(i + 3L, capture.version());
                assertEquals(0, capture.index());
                assertEquals("entity" + (i + 2), capture.entityType());
            }
        } catch (OutsideScopeException e) {
            fail("Should not throw OutsideScopeException");
        }
    }

    /**
     * Fuzzy test for the ring buffer that tests different types of rotations and copy-to operations.
     * For each seed, it creates a random number of captures and adds them to the buffer, then performs
     * random copy-to operations and verifies the results against a control ArrayList.
     *
     * @param seed the random seed to use for the test
     */
    @ParameterizedTest
    @MethodSource("generateRandomSeeds")
    @DisplayName("handle random operations in fuzzy test")
    void shouldHandleRandomOperationsInFuzzyTest(int seed) {
        // Create a random number generator with the given seed
        final Random random = new Random(seed);

        // Create a buffer with a random size between 5 and 20
        final int bufferSize = random.nextInt(16) + 5;
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, bufferSize, ChangeCatalogCapture.class);

        // Create a control ArrayList to verify the results
        final ArrayList<ChangeCatalogCapture> controlList = new ArrayList<>();

        // Add a random number of captures to the buffer (between 10 and 50)
        final int captureCount = random.nextInt(41) + 10;
        for (int i = 0; i < captureCount; i++) {
            final ChangeCatalogCapture capture = createCapture(1L, i, "entity" + i);
            buffer.offer(capture);
            controlList.add(capture);

            // If the control list is larger than the buffer size, remove the oldest items
            while (controlList.size() > bufferSize) {
                controlList.remove(0);
            }
        }

        final ChangeCatalogCapture firstActive = controlList.get(0);

        // Verify the buffer state
        assertEquals(firstActive.version(), buffer.getEffectiveStartCatalogVersion());
        assertEquals(firstActive.index(), buffer.getEffectiveStartIndex());

        // Perform 10 random copy-to operations
        for (int i = 0; i < 10; i++) {
            // Generate a random start index for the copy operation
            final int startIndex = random.nextInt(captureCount - controlList.get(0).index());
            int startSequenceNo = controlList.get(0).index() + startIndex;

            // Create a WAL pointer for the start index
            final WalPointer walPointer = new WalPointer(1L, startSequenceNo);

            // Create a queue to hold the copied captures
            final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();

            try {
                // Copy captures from the buffer
                buffer.copyTo(walPointer, result);

                // Calculate the expected size of the copied captures
                final int expectedSize = controlList.size() - startIndex;

                // Verify the number of copied captures
                assertEquals(expectedSize, result.size(), "Incorrect number of captures copied for start index " + startSequenceNo);

                // Verify the copied captures against the control list
                for (int j = 0; j < expectedSize; j++) {
                    final ChangeCatalogCapture expected = controlList.get(startIndex + j);
                    final ChangeCatalogCapture actual = result.poll();
                    assertEquals(expected, actual, "Incorrect capture at position " + j + " for start index " + startSequenceNo);
                }
            } catch (OutsideScopeException e) {
                // This should not happen since we're skipping start indexes outside the buffer's scope
                fail("Unexpected OutsideScopeException for start index " + startSequenceNo);
            }
        }
    }

    /**
     * Fuzzy test that verifies it's possible to execute `offer` and `copyTo` in parallel
     * using a single (same instance) ring buffer implementation.
     * Verifies that the returned queue always has monotonically increasing index with step of size 1.
     */
    @Test
    @DisplayName("handle parallel offer and copyTo operations")
    void shouldHandleParallelOfferAndCopyToOperations() throws InterruptedException {
        // Create a buffer with a reasonable size
        final int bufferSize = 100;
        final ChangeCaptureRingBuffer<ChangeCatalogCapture> buffer = new ChangeCaptureRingBuffer<>(1L, 0, 10L, bufferSize, ChangeCatalogCapture.class);

        // Create atomic variables for thread coordination
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger nextIndex = new AtomicInteger(0);
        final CountDownLatch startLatch = new CountDownLatch(1);

        // Create a thread pool
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        // Create a list to store all captured results for verification
        final List<List<ChangeCatalogCapture>> allCapturedResults = new ArrayList<>();

        // Create a list to store exceptions
        final List<Exception> exceptions = new CopyOnWriteArrayList<>();

        // Submit the producer task (offer)
        executor.submit(() -> {
            try {
                // Wait for the start signal
                startLatch.await();

                // Keep offering new captures while running
                while (running.get()) {
                    final int index = nextIndex.getAndIncrement();
                    final ChangeCatalogCapture capture = createCapture(1L, index, "entity" + index);
                    buffer.offer(capture);

                    // Small delay to avoid overwhelming the buffer
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                exceptions.add(e);
            }
        });

        // Submit the consumer task (copyTo)
        executor.submit(() -> {
            try {
                // Wait for the start signal
                startLatch.await();

                while (running.get()) {
                    final Queue<ChangeCatalogCapture> result = new ArrayDeque<>();

                    try {
                        // Start from the last read index + 1, or from 0 if this is the first read
                        buffer.copyTo(new WalPointer(1L, buffer.getEffectiveStartIndex() + 10), result);

                        // If we got results, update the last read index and store the results
                        if (!result.isEmpty()) {
                            final List<ChangeCatalogCapture> captureList = new ArrayList<>(result);
                            synchronized (allCapturedResults) {
                                allCapturedResults.add(captureList);
                            }
                        }
                    } catch (Exception e) {
                        exceptions.add(e);
                    }

                    // Small delay to avoid overwhelming the CPU
                    Thread.sleep(5);
                }
            } catch (Exception e) {
                exceptions.add(e);
            }
        });

        // Start both threads
        startLatch.countDown();

        // Let the threads run for a short time
        int sanityCheck = 0;
        do {
            Thread.sleep(25);
            sanityCheck++;
        } while (allCapturedResults.size() < 50 && sanityCheck < 1000);

        // Signal the threads to stop
        running.set(false);

        // Shutdown the executor and wait for the threads to finish
        executor.shutdown();

        // Verify that we have captured some results
        assertTrue(exceptions.isEmpty(), "Exceptions occurred during execution: " + exceptions);

        // Flatten all captured results into a single list
        for (List<ChangeCatalogCapture> captureList : allCapturedResults) {
            // Verify that the indices are monotonically increasing with step size 1
            int previousIndex = -1;
            for (ChangeCatalogCapture capture : captureList) {
                if (previousIndex != -1) {
                    // If this is not the first capture, verify it's exactly one more than the previous
                    assertEquals(
                        previousIndex + 1, capture.index(),
                        "Index should increase by exactly 1, but found jump from " + previousIndex + " to " + capture.index()
                    );
                }
                previousIndex = capture.index();
            }
        }

    }
}
