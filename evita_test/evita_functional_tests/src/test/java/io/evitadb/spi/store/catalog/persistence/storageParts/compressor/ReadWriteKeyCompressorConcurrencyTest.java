/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.spi.store.catalog.persistence.storageParts.compressor;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the concurrency contract of {@link ReadWriteKeyCompressor} after the lock relocation:
 * {@link ReadWriteKeyCompressor#getAtomicSnapshot()} must be coherent with concurrent
 * {@link ReadWriteKeyCompressor#getId(Comparable)} writers, {@link ReadWriteKeyCompressor#executeWithWriteAccess}
 * sessions must allow reentrant `getId(...)` calls, and the memoized snapshot must be correctly
 * invalidated after every insert.
 *
 * The original code (commit `8e9f34157`) defensively copied via `Map.copyOf` on every `getCompressedKeys()`
 * call; the new code returns a live view and relies on the compressor's internal `ReentrantReadWriteLock`
 * to coordinate readers and writers. Each test below documents a specific invariant of the new model.
 */
@Tag(ENGINE)
@Tag(SERIALIZATION)
@DisplayName("ReadWriteKeyCompressor concurrency contract")
class ReadWriteKeyCompressorConcurrencyTest {

	@Nested
	@DisplayName("Atomic snapshot coherence")
	class AtomicSnapshotCoherence {

		@Test
		@DisplayName("getAtomicSnapshot pairs keys.size() with peakId without tearing")
		void shouldReturnCoherentKeysAndPeakIdPair() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			compressor.getId(new AttributeKey("a"));
			compressor.getId(new AttributeKey("b"));

			final KeyCompressorSnapshot snapshot = compressor.getAtomicSnapshot();
			assertEquals(2, snapshot.keys().size());
			assertEquals(2, snapshot.peakId());
		}

		@Test
		@DisplayName("getAtomicSnapshot survives a concurrent writer burst without ConcurrentModificationException")
		void shouldNotThrowConcurrentModificationExceptionUnderLoad() throws Exception {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			final int totalKeys = 10_000;
			final int snapshotReaders = 8;
			final ExecutorService pool = Executors.newFixedThreadPool(snapshotReaders + 1);
			try {
				final CountDownLatch start = new CountDownLatch(1);
				final AtomicReference<Throwable> failure = new AtomicReference<>();

				// one writer thread inserting many keys sequentially
				pool.submit(() -> {
					try {
						start.await();
						for (int i = 0; i < totalKeys; i++) {
							compressor.getId(new AttributeKey("k_" + i));
						}
					} catch (Throwable t) {
						failure.compareAndSet(null, t);
					}
				});

				// many concurrent snapshot readers; each iteration must observe a coherent (keys, peakId) pair
				for (int r = 0; r < snapshotReaders; r++) {
					pool.submit(() -> {
						try {
							start.await();
							for (int i = 0; i < 500; i++) {
								final KeyCompressorSnapshot snap = compressor.getAtomicSnapshot();
								final int keysSize = snap.keys().size();
								final int peakId = snap.peakId();
								// invariants captured under the read lock:
								// keys are append-only and assigned via the monotonic sequence,
								// so peakId must equal keys.size() at the moment of capture
								assertEquals(
									keysSize, peakId,
									"torn (keys, peakId) pair observed: keys=" + keysSize + ", peakId=" + peakId
								);
								// and iterating the immutable snapshot must never throw CME
								int count = 0;
								final Iterator<Map.Entry<Integer, Object>> it = snap.keys().entrySet().iterator();
								while (it.hasNext()) {
									it.next();
									count++;
								}
								assertEquals(keysSize, count);
							}
						} catch (Throwable t) {
							failure.compareAndSet(null, t);
						}
					});
				}

				start.countDown();
				pool.shutdown();
				assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "tasks did not finish within 30s");

				final Throwable t = failure.get();
				if (t != null) {
					throw new AssertionError("concurrent run failed: " + t.getMessage(), t);
				}

				// final state must reflect all inserts
				final KeyCompressorSnapshot finalSnap = compressor.getAtomicSnapshot();
				assertEquals(totalKeys, finalSnap.keys().size());
				assertEquals(totalKeys, finalSnap.peakId());
			} finally {
				pool.shutdownNow();
			}
		}

	}

	@Nested
	@DisplayName("Memoization invalidation")
	class MemoizationInvalidation {

		@Test
		@DisplayName("Repeated snapshot calls without mutations return the same memoized map instance")
		void shouldMemoizeSnapshotWhenNoMutationsOccur() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			compressor.getId(new AttributeKey("a"));

			final Map<Integer, Object> first = compressor.getAtomicSnapshot().keys();
			final Map<Integer, Object> second = compressor.getAtomicSnapshot().keys();
			assertSame(first, second, "memoized snapshot should be reused across calls when no mutation occurred");
		}

		@Test
		@DisplayName("A new getId insert invalidates the memoized snapshot and the next reader sees the fresh key")
		void shouldInvalidateMemoizedSnapshotAfterInsert() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			compressor.getId(new AttributeKey("a"));

			final KeyCompressorSnapshot before = compressor.getAtomicSnapshot();
			final AttributeKey freshKey = new AttributeKey("b");
			final int freshId = compressor.getId(freshKey);
			final KeyCompressorSnapshot after = compressor.getAtomicSnapshot();

			assertEquals(1, before.keys().size());
			assertEquals(2, after.keys().size());
			assertEquals(freshKey, after.keys().get(freshId));
			assertEquals(2, after.peakId());
		}

		@Test
		@DisplayName("getId called for an already-registered key does not invalidate the memoized snapshot")
		void shouldNotInvalidateMemoizedSnapshotForExistingKey() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			final AttributeKey key = new AttributeKey("a");
			compressor.getId(key);

			final Map<Integer, Object> first = compressor.getAtomicSnapshot().keys();
			compressor.getId(key);
			final Map<Integer, Object> second = compressor.getAtomicSnapshot().keys();

			assertSame(first, second, "memoized snapshot should be reused when getId hits an existing key");
		}

	}

	@Nested
	@DisplayName("Write session reentrancy")
	class WriteSessionReentrancy {

		@Test
		@DisplayName("getId works inside executeWithWriteAccess via write-lock reentrancy")
		void shouldAllowGetIdInsideWriteSession() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			final Integer id = compressor.executeWithWriteAccess(() -> {
				final int a = compressor.getId(new AttributeKey("a"));
				final int b = compressor.getId(new AttributeKey("b"));
				return a + b;
			});
			assertEquals(3, id);
			assertEquals(2, compressor.getAtomicSnapshot().keys().size());
		}

		@Test
		@DisplayName("Nested executeWithWriteAccess sessions are allowed (full reentrancy)")
		void shouldSupportNestedWriteSessions() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			final int outerId = compressor.executeWithWriteAccess(() ->
				compressor.executeWithWriteAccess(() -> compressor.getId(new AttributeKey("x")))
			);
			assertTrue(outerId > 0);
		}

		@Test
		@DisplayName("Exception thrown from action releases the write lock so subsequent operations succeed")
		void shouldReleaseWriteLockOnException() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			assertThrows(
				IllegalStateException.class,
				() -> compressor.executeWithWriteAccess(() -> {
					throw new IllegalStateException("boom");
				})
			);
			// if the lock leaked, this call would deadlock on the timeout
			final int id = compressor.getId(new AttributeKey("post-failure"));
			assertEquals(1, id);
		}

	}

	@Nested
	@DisplayName("getKeys live-view contract")
	class GetKeysLiveViewContract {

		@Test
		@DisplayName("getKeys is the live map and reflects subsequent inserts in-place")
		void shouldReturnLiveMapReflectingSubsequentInserts() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			compressor.getId(new AttributeKey("a"));

			final Map<Integer, Object> live = compressor.getKeys();
			final int sizeBeforeInsert = live.size();
			final int newId = compressor.getId(new AttributeKey("b"));
			// the SAME map reference reflects the new insert in-place — this is the documented liveness contract
			assertEquals(sizeBeforeInsert + 1, live.size());
			assertTrue(live.containsKey(newId));
		}

		@Test
		@DisplayName("getAtomicSnapshot returns an immutable copy distinct from the live map")
		void shouldReturnImmutableSnapshotDistinctFromLiveMap() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			compressor.getId(new AttributeKey("a"));

			final Map<Integer, Object> snapshot = compressor.getAtomicSnapshot().keys();
			assertThrows(UnsupportedOperationException.class, () -> snapshot.put(99, new AttributeKey("z")));

			// inserting a new key into the live compressor must NOT mutate the snapshot
			final int sizeBefore = snapshot.size();
			compressor.getId(new AttributeKey("b"));
			assertEquals(sizeBefore, snapshot.size(), "snapshot must be frozen at capture time");
		}

	}

	@Nested
	@DisplayName("Snapshot field cleared by put")
	class SnapshotFieldClearedByPut {

		@Test
		@DisplayName("Subsequent snapshot after an insert is a fresh immutable copy, never null")
		void shouldRebuildSnapshotAfterInvalidation() {
			final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
			compressor.getId(new AttributeKey("a"));

			final KeyCompressorSnapshot s1 = compressor.getAtomicSnapshot();
			assertNotNull(s1.keys());
			compressor.getId(new AttributeKey("b"));
			final KeyCompressorSnapshot s2 = compressor.getAtomicSnapshot();
			assertNotNull(s2.keys());

			// the snapshots are different instances (s1's immutable map is frozen at one key,
			// s2 must reflect both keys)
			assertEquals(1, s1.keys().size());
			assertEquals(2, s2.keys().size());
		}

	}

	/**
	 * Sanity check that the documented invariant `max(keys.keySet()) <= peakId` always holds. The
	 * compressor uses a monotonic sequence to allocate ids; the snapshot must never expose ids
	 * greater than `peakId` (would indicate sequence corruption).
	 */
	@Test
	@DisplayName("Snapshot never exposes ids greater than peakId")
	void shouldRespectMaxKeyIdLessThanOrEqualToPeakIdInvariant() {
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
		for (int i = 0; i < 50; i++) {
			compressor.getId(new AttributeKey("k_" + i));
		}
		final KeyCompressorSnapshot snap = compressor.getAtomicSnapshot();
		final int max = snap.keys().keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
		assertTrue(max <= snap.peakId(), "max(keys.keySet())=" + max + " must be <= peakId=" + snap.peakId());
	}

}
