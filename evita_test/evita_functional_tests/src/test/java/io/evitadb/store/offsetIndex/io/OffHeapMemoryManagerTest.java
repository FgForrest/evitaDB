/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * Tests for {@link CatalogOffHeapMemoryManager} focusing on:
 * - region acquisition and release lifecycle
 * - read/write semantics of paired off-heap streams
 * - boundary checks and region exhaustion behavior
 *
 * These tests exercise low-level off-heap memory management to ensure safety and correctness.
 *
 * The class runs isolated: the tests that check the memory block is freed read the JVM's direct buffer pool, and
 * a transaction buffer another test allocates in the same moment would hide the freed block.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("CatalogOffHeapMemoryManager off-heap regions and stream semantics")
@Tag(STORAGE)
@Tag(MANAGEMENT)
@Isolated
class OffHeapMemoryManagerTest {
	/**
	 * Size of the memory block in the tests that observe the block being freed. It is large enough that a drop of half
	 * of it in the JVM's direct memory cannot come from the small buffers background work allocates meanwhile.
	 */
	private static final int LARGE_BLOCK_SIZE = 32 * 1024 * 1024;
	private final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
		TestConstants.TEST_CATALOG, 1024, 16, ChecksumFactory.NO_OP
	);

	/**
	 * Verifies that a region output stream can be acquired, written to, and read back
	 * via its paired input stream. Also checks the free region counter is decremented while
	 * the stream is held and restored once it is closed.
	 */
	@Test
	@DisplayName("should write to region and read it back via paired input stream")
	void shouldAcquireOutputStreamWriteOutputAndReadItAgain() throws IOException {
		try (final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow()) {
			assertEquals(15, this.memoryManager.getFreeRegions());

			final var expected = "Hello world!";
			// when
			outputStream.write(expected.getBytes());
			final var inputStream = outputStream.getInputStream();
			final var actual = new String(inputStream.readAllBytes());

			assertEquals(expected, actual);
		}
		assertEquals(16, this.memoryManager.getFreeRegions());
	}

	/**
	 * Ensures writing to the output stream after it has been explicitly closed
	 * results in a runtime failure.
	 */
	@Test
	@DisplayName("should fail to write after output stream is closed")
	void shouldFailToWriteToStreamAfterItWasClosed() {
		final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow();
		outputStream.close();
		assertThrows(RuntimeException.class, () -> outputStream.write("Hello world!".getBytes()));
	}

	/**
	 * Ensures reading from the input stream after the input stream itself is closed
	 * fails with a runtime exception.
	 */
	@Test
	@DisplayName("should fail to read after input stream is closed")
	void shouldFailToReadFromStreamAfterItWasClosed() {
		final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow();
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();
		inputStream.close();
		assertThrows(RuntimeException.class, inputStream::read);
	}

	/**
	 * Ensures that closing the producing output stream invalidates the derived input stream.
	 */
	@Test
	@DisplayName("should fail to read when original output stream is closed")
	void shouldFailToReadFromStreamAfterOriginalOutputStreamWasClosed() {
		final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow();
		final var inputStream = outputStream.getInputStream();
		outputStream.close();
		assertThrows(RuntimeException.class, inputStream::read);
	}

	/**
	 * Validates that attempting to write beyond the fixed-size region throws BufferOverflowException
	 * and that bytes written within bounds affect only the allocated region while other memory
	 * remains zeroed.
	 */
	@Test
	@DisplayName("should prevent writes beyond region and not affect other regions")
	void shouFailToWriteBytesOutsideTheRegion() throws IOException {
		try (
			final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow()
		) {
			final int regionIndex = outputStream.getRegionIndex();
			for (int i = 0; i < 64; i++) {
				outputStream.write((byte) 1);
			}
			// writing byte over the limit should fail
			assertThrows(BufferOverflowException.class, () -> outputStream.write((byte) 1));
			// check only data that fit the region were set
			final byte[] fullMemoryBlockCopy = new byte[1024];
			this.memoryManager.memoryBlock.get().get(fullMemoryBlockCopy, 0, 1024);
			for (int i = 0; i < 1024; i++) {
				if (i >= regionIndex << 6 && i < (regionIndex << 6) + 64) {
					assertEquals(1, fullMemoryBlockCopy[i], "Byte at index " + i + " should be 1!");
				} else {
					assertEquals(0, fullMemoryBlockCopy[i], "Byte at index " + i + " should be 0!");
				}
			}
		}
	}

	/**
	 * Verifies that only a fixed number of regions can be acquired and that further acquisition
	 * attempts fail once the pool is exhausted. Also asserts the free region counter.
	 */
	@Test
	@DisplayName("should not acquire stream when free regions are exhausted")
	void shouldFailToAcquireOutputStreamWhenFreeRegionsAreExhausted() {
		for (int i = 0; i < 16; i++) {
			assertTrue(this.memoryManager.acquireRegionOutputStream().isPresent());
		}

		assertFalse(this.memoryManager.acquireRegionOutputStream().isPresent());
		assertEquals(0, this.memoryManager.getFreeRegions());
		this.memoryManager.close();
	}

	/**
	 * Verifies that the manager allocates its memory block only when the first region is acquired, so an engine or
	 * a catalog that never runs a transaction holds no off-heap memory.
	 */
	@Test
	@DisplayName("should allocate the memory block only when the first region is acquired")
	void shouldAllocateMemoryBlockOnlyWhenFirstRegionIsAcquired() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, 1024, 16, ChecksumFactory.NO_OP
			)
		) {
			assertNull(memoryManager.memoryBlock.get());
			try (final OffHeapMemoryOutputStream ignored = memoryManager.acquireRegionOutputStream().orElseThrow()) {
				assertNotNull(memoryManager.memoryBlock.get());
				assertEquals(15, memoryManager.getFreeRegions());
			}
		}
	}

	/**
	 * Verifies that closing a manager whose regions were all released frees its memory block at once, which the JVM's
	 * direct buffer pool shows without any garbage collection.
	 */
	@Test
	@DisplayName("should free the memory block when closed with every region released")
	void shouldFreeMemoryBlockWhenClosedWithEveryRegionReleased() throws IOException {
		final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
			TestConstants.TEST_CATALOG, LARGE_BLOCK_SIZE, 16, ChecksumFactory.NO_OP
		);
		try (final OffHeapMemoryOutputStream outputStream = memoryManager.acquireRegionOutputStream().orElseThrow()) {
			outputStream.write(1);
		}

		final long directMemoryBeforeClose = getDirectMemoryUsed();
		memoryManager.close();

		assertNull(memoryManager.memoryBlock.get());
		assertFreed(directMemoryBeforeClose);
	}

	/**
	 * Verifies that closing the manager while a region is still in use leaves the region's memory readable - a WAL
	 * reference may still be copying it - and that the block is freed once that last region is released.
	 */
	@Test
	@DisplayName("should keep a region in use readable after close and free the block when it is released")
	void shouldFreeMemoryBlockWhenLastRegionInUseIsReleasedAfterClose() throws IOException {
		final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
			TestConstants.TEST_CATALOG, LARGE_BLOCK_SIZE, 16, ChecksumFactory.NO_OP
		);
		final OffHeapMemoryOutputStream outputStream = memoryManager.acquireRegionOutputStream().orElseThrow();
		outputStream.write("Hello world!".getBytes());

		memoryManager.close();

		assertNotNull(memoryManager.memoryBlock.get());
		assertEquals("Hello world!", new String(outputStream.getInputStream().readAllBytes()));
		assertTrue(memoryManager.acquireRegionOutputStream().isEmpty());

		final long directMemoryBeforeRelease = getDirectMemoryUsed();
		outputStream.close();

		assertNull(memoryManager.memoryBlock.get());
		assertEquals(16, memoryManager.getFreeRegions());
		assertFreed(directMemoryBeforeRelease);
	}

	/**
	 * Verifies that a closed manager hands out no region and allocates no memory for one.
	 */
	@Test
	@DisplayName("should acquire no region after close")
	void shouldAcquireNoRegionAfterClose() {
		final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
			TestConstants.TEST_CATALOG, 1024, 16, ChecksumFactory.NO_OP
		);
		memoryManager.close();

		assertTrue(memoryManager.acquireRegionOutputStream().isEmpty());
		assertNull(memoryManager.memoryBlock.get());
		assertEquals(16, memoryManager.getFreeRegions());
	}

	/**
	 * Verifies that closing the manager again changes nothing: the block the first close freed stays freed and every
	 * region stays free.
	 */
	@Test
	@DisplayName("should ignore a repeated close")
	void shouldIgnoreRepeatedClose() {
		final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
			TestConstants.TEST_CATALOG, 1024, 16, ChecksumFactory.NO_OP
		);
		memoryManager.acquireRegionOutputStream().orElseThrow().close();
		memoryManager.close();

		memoryManager.close();

		assertNull(memoryManager.memoryBlock.get());
		assertEquals(16, memoryManager.getFreeRegions());
	}

	/**
	 * Verifies that an open manager keeps its memory block when every region is released, so that the transactions
	 * that follow reuse it rather than allocate a block each.
	 */
	@Test
	@DisplayName("should keep the memory block for later regions while open")
	void shouldReuseMemoryBlockWhileManagerIsOpen() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, 1024, 16, ChecksumFactory.NO_OP
			)
		) {
			final OffHeapMemoryOutputStream outputStream = memoryManager.acquireRegionOutputStream().orElseThrow();
			final ByteBuffer memoryBlock = memoryManager.memoryBlock.get();

			outputStream.close();

			assertNotNull(memoryBlock);
			assertSame(memoryBlock, memoryManager.memoryBlock.get());
			try (final OffHeapMemoryOutputStream ignored = memoryManager.acquireRegionOutputStream().orElseThrow()) {
				assertSame(memoryBlock, memoryManager.memoryBlock.get());
			}
		}
	}

	/**
	 * Verifies that a manager with no memory or no regions hands out no region and allocates no memory block.
	 */
	@Test
	@DisplayName("should hand out no region when the manager has no capacity")
	void shouldHandOutNoRegionWhenManagerHasNoCapacity() {
		try (
			final CatalogOffHeapMemoryManager noMemory = new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, 0, 16, ChecksumFactory.NO_OP
			);
			final CatalogOffHeapMemoryManager noRegions = new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, 1024, 0, ChecksumFactory.NO_OP
			)
		) {
			final Optional<OffHeapMemoryOutputStream> regionWithNoMemory = noMemory.acquireRegionOutputStream();
			final Optional<OffHeapMemoryOutputStream> regionWithNoRegions = noRegions.acquireRegionOutputStream();

			assertTrue(regionWithNoMemory.isEmpty());
			assertTrue(regionWithNoRegions.isEmpty());
			assertNull(noMemory.memoryBlock.get());
			assertNull(noRegions.memoryBlock.get());
		}
	}

	/**
	 * Verifies that a memory block larger than a direct buffer can hold is refused when the manager is created - as
	 * a configuration error at startup - rather than by the first transaction that needs the block.
	 */
	@Test
	@DisplayName("should refuse a memory block larger than a direct buffer can hold when constructed")
	void shouldRefuseMemoryBlockLargerThanDirectBufferCanHoldWhenConstructed() {
		assertThrows(
			ArithmeticException.class,
			() -> new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, Integer.MAX_VALUE + 1L, 2, ChecksumFactory.NO_OP
			)
		);
	}

	/**
	 * Verifies that a region released while the manager closes leaves the allocation gauge at zero: the release
	 * reports the memory in use before the close frees the block, or not at all, never after the block was freed.
	 */
	@Test
	@DisplayName("should report no allocated memory when a region release races with close")
	void shouldReportNoAllocatedMemoryWhenRegionReleaseRacesWithClose() throws InterruptedException {
		final List<long[]> events = new CopyOnWriteArrayList<>();
		final AtomicReference<Thread> releaserThread = new AtomicReference<>();
		final CountDownLatch releaserParked = new CountDownLatch(1);
		final CountDownLatch releaserResumes = new CountDownLatch(1);
		final OffHeapMemoryManager memoryManager = new OffHeapMemoryManager(1024, 16, ChecksumFactory.NO_OP) {
			@Override
			protected void emitAllocationEvent(long allocatedMemoryBytes, long usedMemoryBytes) {
				if (Thread.currentThread() == releaserThread.get() && releaserParked.getCount() > 0) {
					// hold the release just before it reports, so that the close runs in between
					releaserParked.countDown();
					try {
						assertTrue(releaserResumes.await(1, TimeUnit.MINUTES));
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(ex);
					}
				}
				events.add(new long[]{allocatedMemoryBytes, usedMemoryBytes});
			}
		};
		final OffHeapMemoryOutputStream region = memoryManager.acquireRegionOutputStream().orElseThrow();
		final Thread releasing = new Thread(region::close);
		releaserThread.set(releasing);
		final Thread closing = new Thread(memoryManager::close);

		try {
			releasing.start();
			assertTrue(releaserParked.await(1, TimeUnit.MINUTES));
			closing.start();
			// the close either completes at once, or waits for the parked release to report
			final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
			Thread.State closingState = closing.getState();
			while (closingState != Thread.State.TERMINATED && closingState != Thread.State.WAITING) {
				assertTrue(System.nanoTime() < deadline, "The close neither completed nor waited for the release.");
				Thread.onSpinWait();
				closingState = closing.getState();
			}
		} finally {
			releaserResumes.countDown();
		}
		releasing.join();
		closing.join();

		assertNull(memoryManager.memoryBlock.get());
		assertArrayEquals(new long[]{0L, 0L}, events.get(events.size() - 1));
	}

	/**
	 * Verifies that regions acquired, written, read and released concurrently with the manager's close never see each
	 * other's bytes, that every one of them is returned and that the block ends up freed.
	 */
	@Test
	@DisplayName("should free the memory block when regions are used concurrently with close")
	void shouldFreeMemoryBlockWhenRegionsAreUsedConcurrentlyWithClose() throws Exception {
		final int threadCount = 8;
		final int regionSize = 1024;
		final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
			TestConstants.TEST_CATALOG, 64L * regionSize, 64, ChecksumFactory.NO_OP
		);
		final AtomicInteger acquisitions = new AtomicInteger();
		final CountDownLatch started = new CountDownLatch(threadCount);
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			final List<Future<Integer>> results = new ArrayList<>(threadCount);
			for (int i = 0; i < threadCount; i++) {
				final byte[] payload = new byte[regionSize];
				Arrays.fill(payload, (byte) i);
				results.add(
					executor.submit(
						() -> {
							started.countDown();
							int acquired = 0;
							// each thread holds one region at most, so an empty result means the manager is closed
							Optional<OffHeapMemoryOutputStream> region = memoryManager.acquireRegionOutputStream();
							while (region.isPresent()) {
								try (final OffHeapMemoryOutputStream outputStream = region.get()) {
									outputStream.write(payload);
									assertArrayEquals(payload, outputStream.getInputStream().readAllBytes());
								}
								acquired++;
								acquisitions.incrementAndGet();
								region = memoryManager.acquireRegionOutputStream();
							}
							return acquired;
						}
					)
				);
			}
			started.await();
			while (acquisitions.get() < 10_000) {
				Thread.onSpinWait();
			}
			memoryManager.close();
			int acquiredByThreads = 0;
			for (Future<Integer> result : results) {
				acquiredByThreads += result.get(1, TimeUnit.MINUTES);
			}
			assertEquals(acquisitions.get(), acquiredByThreads);
		} finally {
			executor.shutdownNow();
		}

		assertNull(memoryManager.memoryBlock.get());
		assertEquals(64, memoryManager.getFreeRegions());
	}

	/**
	 * Asserts that the JVM's direct memory dropped by at least half of {@link #LARGE_BLOCK_SIZE} since the given
	 * reading.
	 *
	 * @param directMemoryBefore the direct memory used before the block was expected to be freed
	 */
	private static void assertFreed(long directMemoryBefore) {
		final long freed = directMemoryBefore - getDirectMemoryUsed();
		assertTrue(
			freed >= LARGE_BLOCK_SIZE / 2,
			"The memory block should have been freed at once, but direct memory dropped by " + freed + " bytes only."
		);
	}

	/**
	 * Returns the direct memory the JVM's direct buffer pool reports as used.
	 *
	 * @return the direct memory used in bytes
	 */
	private static long getDirectMemoryUsed() {
		for (BufferPoolMXBean bufferPool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
			if ("direct".equals(bufferPool.getName())) {
				return bufferPool.getMemoryUsed();
			}
		}
		throw new IllegalStateException("The JVM reports no direct buffer pool.");
	}

}
