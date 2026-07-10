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

package io.evitadb.store.kryo;

import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryOutputStream;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies the keyless off-heap {@link ObservableOutput} free-list maintained by
 * {@link ObservableOutputKeeper}: borrowing falls back to the caller-supplied factory when the free-list is
 * empty, recycled instances are handed out (rebound to the new stream) instead of allocating fresh ones, and
 * the free-list is dropped both on idle eviction and on {@link ObservableOutputKeeper#close()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("ObservableOutputKeeper off-heap free-list functionality")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class ObservableOutputKeeperTest {
	private final ObservableOutputKeeper keeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
	private final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
		TestConstants.TEST_CATALOG, 65_536, 4, ChecksumFactory.NO_OP
	);

	@AfterEach
	void tearDown() {
		this.keeper.close();
		this.memoryManager.close();
	}

	@Test
	@DisplayName("Should construct a fresh output via the factory when the free-list is empty")
	void shouldConstructFreshOutputWhenFreeListEmpty() {
		final AtomicInteger createCount = new AtomicInteger();
		final OffHeapMemoryOutputStream stream = acquireStream();
		final ObservableOutput<OffHeapMemoryOutputStream> created = newObservableOutput(stream);

		final ObservableOutput<OffHeapMemoryOutputStream> output = this.keeper.borrowOffHeapOutput(
			stream,
			() -> {
				createCount.incrementAndGet();
				return created;
			}
		);

		assertNotNull(output);
		assertEquals(1, createCount.get());
		assertSame(created, output, "an empty free-list must hand back the factory-produced instance");
		assertEquals(0, getFreeListSize(), "constructing a fresh output must leave the free-list empty");
	}

	@Test
	@DisplayName("Should hand out a recycled instance instead of invoking the factory")
	void shouldReuseRecycledInstance() {
		final OffHeapMemoryOutputStream streamA = acquireStream();
		final ObservableOutput<OffHeapMemoryOutputStream> instance = newObservableOutput(streamA);
		this.keeper.recycleOffHeapOutput(instance);
		assertEquals(1, getFreeListSize());

		final OffHeapMemoryOutputStream streamB = acquireStream();
		final AtomicInteger createCount = new AtomicInteger();
		final ObservableOutput<OffHeapMemoryOutputStream> borrowed = this.keeper.borrowOffHeapOutput(
			streamB,
			() -> {
				createCount.incrementAndGet();
				return newObservableOutput(streamB);
			}
		);

		assertSame(instance, borrowed, "the recycled instance must be returned instead of a fresh one");
		assertEquals(0, createCount.get(), "the factory must not run when a recycled instance is available");
		assertEquals(0, getFreeListSize(), "the borrow must drain the free-list");
		assertSame(streamB, borrowed.getOutputStream(), "the recycled instance must be rebound to the new stream");
	}

	@Test
	@DisplayName("Should evict the free-list once it has been idle past the inactivity threshold")
	void shouldEvictFreeListAfterInactivity() throws ReflectiveOperationException {
		this.keeper.recycleOffHeapOutput(newObservableOutput(acquireStream()));
		assertEquals(1, getFreeListSize());

		// simulate the free-list having been untouched for longer than the 5-minute inactivity threshold
		setLastOffHeapActivityTime(System.currentTimeMillis() - 400_000L);
		invokeCutOutputCache();

		assertEquals(0, getFreeListSize(), "an idle free-list must be dropped");
	}

	@Test
	@DisplayName("Should not evict the free-list while it is still within the inactivity threshold")
	void shouldNotEvictFreeListWhileActive() throws ReflectiveOperationException {
		this.keeper.recycleOffHeapOutput(newObservableOutput(acquireStream()));
		assertEquals(1, getFreeListSize());

		invokeCutOutputCache();

		assertEquals(1, getFreeListSize(), "a recently-touched free-list must survive a cut cycle");
	}

	@Test
	@DisplayName("Should refresh the inactivity timestamp when an output is recycled")
	void shouldRefreshInactivityTimestampOnRecycle() throws ReflectiveOperationException {
		// back-date the pool's activity timestamp well past the inactivity threshold
		setLastOffHeapActivityTime(System.currentTimeMillis() - 400_000L);

		// recycling must re-stamp the timestamp to "now" so the freshly recycled instance is protected
		this.keeper.recycleOffHeapOutput(newObservableOutput(acquireStream()));
		assertEquals(1, getFreeListSize());

		invokeCutOutputCache();

		assertEquals(
			1, getFreeListSize(),
			"recycle must refresh the activity timestamp so the recycled instance survives the next cut cycle"
		);
	}

	@Test
	@DisplayName("Should clear the free-list on close")
	void shouldClearFreeListOnClose() {
		this.keeper.recycleOffHeapOutput(newObservableOutput(acquireStream()));
		assertEquals(1, getFreeListSize());

		this.keeper.close();

		assertEquals(0, getFreeListSize());
	}

	@Nonnull
	private OffHeapMemoryOutputStream acquireStream() {
		return this.memoryManager.acquireRegionOutputStream().orElseThrow();
	}

	@Nonnull
	private static ObservableOutput<OffHeapMemoryOutputStream> newObservableOutput(@Nonnull OffHeapMemoryOutputStream stream) {
		final ObservableOutput<OffHeapMemoryOutputStream> output = new ObservableOutput<>(
			stream, ObservableOutput.DEFAULT_FLUSH_SIZE, 0, ChecksumFactory.NO_OP.createChecksum(), null
		);
		output.markCumulativeChecksumStart();
		return output;
	}

	private int getFreeListSize() {
		try {
			final Field field = ObservableOutputKeeper.class.getDeclaredField("freeOffHeapOutputs");
			field.setAccessible(true);
			return ((ConcurrentLinkedDeque<?>) field.get(this.keeper)).size();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private void setLastOffHeapActivityTime(long value) throws ReflectiveOperationException {
		final Field field = ObservableOutputKeeper.class.getDeclaredField("lastOffHeapActivityTime");
		field.setAccessible(true);
		((AtomicLong) field.get(this.keeper)).set(value);
	}

	private void invokeCutOutputCache() throws ReflectiveOperationException {
		final Method method = ObservableOutputKeeper.class.getDeclaredMethod("cutOutputCache");
		method.setAccessible(true);
		method.invoke(this.keeper);
	}
}
