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

import io.evitadb.core.metric.event.storage.ObservableOutputChangeEvent;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies exact-size reuse, bounded idle memory, exclusive ownership and JVM-wide JFR accounting.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Shared frame buffer pool")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Isolated
class ObservableOutputBufferPoolTest {
	private final ObservableOutputBufferPool pool = ObservableOutputBufferPool.INSTANCE;
	@TempDir private Path directory;

	/** Exact-size stacks retain a fixed number of exclusively owned arrays. */
	@Nested
	@DisplayName("Buffer ownership")
	class Ownership {
		/** Returned buffers can be reused only by requests for their exact size. */
		@Test
		@DisplayName("Should reuse arrays and keep exact sizes apart")
		void shouldReuseArraysAndKeepExactSizesApart() {
			final byte[] first = pool.borrow(137);
			final byte[] second = pool.borrow(139);
			assertEquals(137, first.length);
			assertEquals(139, second.length);
			pool.release(first);
			pool.release(second);
			final byte[] reusedFirst = pool.borrow(137);
			final byte[] reusedSecond = pool.borrow(139);
			assertSame(first, reusedFirst);
			assertSame(second, reusedSecond);
			pool.release(reusedFirst);
			pool.release(reusedSecond);
		}

		/** Excess idle arrays are discarded instead of increasing the retained ceiling. */
		@Test
		@DisplayName("Should retain at most one idle array per core and size")
		void shouldBoundIdleArraysByCoreCount() {
			final int cores = Runtime.getRuntime().availableProcessors();
			final List<byte[]> borrowed = new ArrayList<>(cores + 2);
			final Set<byte[]> originals = Collections.newSetFromMap(new IdentityHashMap<>());
			for (int i = 0; i < cores + 2; i++) {
				final byte[] buffer = pool.borrow(149);
				borrowed.add(buffer);
				originals.add(buffer);
			}
			borrowed.forEach(pool::release);
			borrowed.clear();
			for (int i = 0; i < cores + 1; i++) {
				final byte[] buffer = pool.borrow(149);
				borrowed.add(buffer);
				assertEquals(i < cores, originals.contains(buffer));
			}
			borrowed.forEach(pool::release);
		}

		/**
		 * Many live outputs writing sequentially on one thread share at most one borrowed assembly array.
		 */
		@Test
		@DisplayName("Should borrow at most one buffer across sequential outputs on one thread")
		void shouldBorrowAtMostOneBufferAcrossSequentialOutputs() {
			final List<ObservableOutput<ByteArrayOutputStream>> outputs = new ArrayList<>(200);
			try {
				for (int i = 0; i < 200; i++) {
					outputs.add(new ObservableOutput<>(
						new ByteArrayOutputStream(), 163, 0L, Checksum.NO_OP, null, pool
					));
				}
				for (ObservableOutput<ByteArrayOutputStream> output : outputs) {
					new StorageRecord<>(output, 1L, false, out -> {
						out.writeInt(42);
						int borrowedCount = 0;
						for (ObservableOutput<ByteArrayOutputStream> candidate : outputs) {
							if (candidate.getBuffer().length != 0) {
								borrowedCount++;
							}
						}
						assertEquals(1, borrowedCount);
						return 42;
					});
					assertEquals(0, output.getBuffer().length);
				}
			} finally {
				outputs.forEach(ObservableOutput::close);
			}
		}

		/**
		 * Latches keep all holders alive together, deterministically checking exclusive ownership.
		 *
		 * @throws Exception if concurrent borrowers fail or do not finish within the timeout
		 */
		@Test
		@DisplayName("Should never hand one array to concurrent holders")
		void shouldKeepConcurrentHoldersExclusive() throws Exception {
			final int holders = 24;
			final CountDownLatch borrowed = new CountDownLatch(holders);
			final CountDownLatch release = new CountDownLatch(1);
			final Set<byte[]> active = Collections.synchronizedSet(
				Collections.newSetFromMap(new IdentityHashMap<>())
			);
			try (final ExecutorService executor = Executors.newFixedThreadPool(
				holders, Thread.ofPlatform().daemon().factory()
			)) {
				final List<Future<?>> futures = new ArrayList<>(holders);
				try {
					for (int i = 0; i < holders; i++) {
						futures.add(executor.submit(() -> {
							final byte[] buffer = pool.borrow(151);
							try {
								assertTrue(active.add(buffer));
								borrowed.countDown();
								assertTrue(release.await(30, TimeUnit.SECONDS));
							} finally {
								active.remove(buffer);
								pool.release(buffer);
							}
							return null;
						}));
					}
					assertTrue(borrowed.await(30, TimeUnit.SECONDS));
					assertEquals(holders, active.size());
				} finally {
					release.countDown();
				}
				for (Future<?> future : futures) {
					future.get(30, TimeUnit.SECONDS);
				}
			}
		}
	}

	/** Event counts include both borrowed and idle arrays, across every exact size. */
	@Nested
	@DisplayName("JFR accounting")
	class Events {
		/**
		 * Only allocation and dropping change the array set; borrowing and returning retained arrays emit nothing.
		 *
		 * @throws Exception if the JFR recording cannot be written or read
		 */
		@Test
		@DisplayName("Should report borrowed plus idle arrays and drop excess memory")
		void shouldReportBorrowedAndIdleMemory() throws Exception {
			final int count = Runtime.getRuntime().availableProcessors() + 1;
			final int size = 157;
			final List<byte[]> buffers = new ArrayList<>(count);
			final Path recordingPath = directory.resolve("buffers.jfr");
			try (final Recording recording = new Recording()) {
				recording.enable(ObservableOutputChangeEvent.class).withoutStackTrace();
				recording.start();
				for (int i = 0; i < count; i++) {
					buffers.add(pool.borrow(size));
				}
				buffers.forEach(pool::release);
				for (int i = 0; i < count * 2; i++) {
					pool.release(pool.borrow(size));
				}
				recording.stop();
				recording.dump(recordingPath);
			}
			final List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath).stream()
				.filter(event -> event.getEventType().getName().endsWith(".ObservableOutputChange"))
				.toList();
			assertEquals(count + 1, events.size());
			assertNull(events.getFirst().getString("catalogName"));
			final int initialCount = events.getFirst().getInt("openedBuffers");
			final long initialMemory = events.getFirst().getLong("occupiedMemoryBytes");
			for (int i = 0; i < count; i++) {
				assertEquals(initialCount + i, events.get(i).getInt("openedBuffers"));
				assertEquals(initialMemory + (long) i * size, events.get(i).getLong("occupiedMemoryBytes"));
			}
			assertEquals(initialCount + count - 2, events.getLast().getInt("openedBuffers"));
			assertEquals(initialMemory + (long) (count - 2) * size,
				events.getLast().getLong("occupiedMemoryBytes"));
		}
	}
}
