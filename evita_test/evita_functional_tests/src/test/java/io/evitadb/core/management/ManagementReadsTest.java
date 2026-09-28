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

package io.evitadb.core.management;

import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.map.PersistentTransactionalMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ManagementReads} answers a management reader while a warm-up writer mutates the structure it
 * reads in place.
 *
 * The writer is placed deterministically: the fixture's keys call back into the test from `hashCode()`, which every
 * copy of the map invokes once per entry it adds to the snapshot it builds. A key armed to write therefore lands a
 * warm-up write between two entries of the walk - the same point a concurrent writer's store can land at, and the
 * point at which `HashMap`'s fail-fast iteration notices it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Management reads of live structures")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class ManagementReadsTest {
	/**
	 * Entries of every fixture map - few enough that a thawed copy sits on a sixteen-slot table, whose resize
	 * threshold of twelve the one extra key the writer toggles never reaches.
	 */
	private static final int ENTRIES = 8;
	/**
	 * Id of the key the warm-up writer adds - outside the fixture's own range.
	 */
	private static final int WRITTEN_KEY = 1_000;

	/**
	 * Builds a map in the warm-up shape - its state a thawed `HashMap` rather than a sealed trie, which is what a
	 * non-transactional write leaves behind.
	 *
	 * The armed key takes id `0`, so it sits in the first bucket of the table and is copied first: the write it
	 * triggers lands with the rest of the walk still ahead of it, rather than after the last entry, where a
	 * per-step fail-fast check would never get to see it.
	 *
	 * @param armed the fixture key that calls back into the test, with id `0`
	 * @return the map, thawed
	 */
	@Nonnull
	private static PersistentTransactionalMap<CallbackKey, Integer> thawedMapWith(@Nonnull CallbackKey armed) {
		final Map<CallbackKey, Integer> seed = new HashMap<>();
		seed.put(armed, armed.id());
		for (int i = 1; i < ENTRIES; i++) {
			seed.put(new CallbackKey(i, null), i);
		}
		return new PersistentTransactionalMap<>(seed);
	}

	@Nested
	@DisplayName("snapshot of an index map")
	class SnapshotOf {

		@Test
		@DisplayName("copies the map a warm-up write lands in while it is being copied")
		void shouldSnapshotAMapThatAWriteLandsInWhileItIsCopied() {
			final AtomicReference<PersistentTransactionalMap<CallbackKey, Integer>> map = new AtomicReference<>();
			final boolean[] written = {false};
			final CallbackKey armed = new CallbackKey(
				0,
				() -> {
					// silent while the fixture itself is being built, which hashes this key too
					if (map.get() != null && !written[0]) {
						written[0] = true;
						map.get().put(new CallbackKey(WRITTEN_KEY, null), WRITTEN_KEY);
					}
				}
			);
			map.set(thawedMapWith(armed));

			final ChampMap<CallbackKey, Integer> snapshot = ManagementReads.snapshotOf(map.get());

			assertTrue(written[0], "the write must have landed during the copy");
			// the write landed before the copy that succeeded, so that copy holds it
			assertEquals(ENTRIES + 1, snapshot.size());
			for (int i = 0; i < ENTRIES; i++) {
				assertEquals(i, snapshot.get(new CallbackKey(i, null)));
			}
			assertEquals(WRITTEN_KEY, snapshot.get(new CallbackKey(WRITTEN_KEY, null)));
		}

		@Test
		@DisplayName("still answers when a write lands in every copy it attempts")
		void shouldStillAnswerWhenAWriteLandsInEveryCopy() {
			// the writer toggles one extra key in and out on every entry the copy adds: every attempt meets a write,
			// and the table never reaches its resize threshold
			final AtomicReference<PersistentTransactionalMap<CallbackKey, Integer>> map = new AtomicReference<>();
			final CallbackKey extra = new CallbackKey(WRITTEN_KEY, null);
			final int[] writes = {0};
			final CallbackKey armed = new CallbackKey(
				0,
				() -> {
					// silent while the fixture itself is being built, which hashes this key too
					final PersistentTransactionalMap<CallbackKey, Integer> live = map.get();
					if (live != null) {
						if (live.remove(extra) == null) {
							live.put(extra, WRITTEN_KEY);
						}
						writes[0]++;
					}
				}
			);
			map.set(thawedMapWith(armed));

			final ChampMap<CallbackKey, Integer> snapshot = ManagementReads.snapshotOf(map.get());
			// disarm before reading the answer back, so the assertions below cannot write
			map.set(null);

			assertTrue(writes[0] > 1, "a write must have landed in more than one copy, there were " + writes[0]);
			// every fixture entry was present throughout, so the answer holds all of them; the toggled key may or may
			// not have been in place when the walk passed its slot
			for (int i = 0; i < ENTRIES; i++) {
				assertEquals(i, snapshot.get(new CallbackKey(i, null)));
			}
			final Integer toggled = snapshot.get(extra);
			assertEquals(toggled == null ? ENTRIES : ENTRIES + 1, snapshot.size());
			if (toggled != null) {
				assertEquals(WRITTEN_KEY, toggled);
			}
		}

		@Test
		@DisplayName("hands back the sealed trie itself, without copying it")
		void shouldReturnTheSealedTrieWithoutCopying() {
			final PersistentTransactionalMap<CallbackKey, Integer> map = thawedMapWith(new CallbackKey(0, null));
			final ChampMap<CallbackKey, Integer> sealed = map.sealed();

			assertSame(sealed, ManagementReads.snapshotOf(map), "a sealed map must be answered in O(1)");
		}
	}

	@Nested
	@DisplayName("tolerant walk")
	class WalkTolerantly {

		@Test
		@DisplayName("answers with the last attempt, complete for what it reached, when a write lands in every attempt")
		void shouldAnswerWithTheLastAttemptWhenAWriteLandsInEveryAttempt() {
			final Map<Integer, Integer> live = new HashMap<>();
			for (int i = 0; i < ENTRIES; i++) {
				live.put(i, i);
			}
			final int[] attempts = {0};

			final List<Integer> keys = ManagementReads.<List<Integer>>walkTolerantly(
				() -> {
					attempts[0]++;
					return new ArrayList<>();
				},
				visited -> live.forEach((key, value) -> {
					visited.add(key);
					// the warm-up writer toggles one extra key on every entry - no attempt can finish undisturbed
					if (live.remove(WRITTEN_KEY) == null) {
						live.put(WRITTEN_KEY, WRITTEN_KEY);
					}
				})
			);

			assertEquals(ManagementReads.MAX_ATTEMPTS, attempts[0], "every attempt must have been made, and no more");
			// `forEach` visited the whole table before it threw, so every entry present throughout was reached
			for (int i = 0; i < ENTRIES; i++) {
				assertTrue(keys.contains(i), "entry " + i + " must have been reached");
			}
			assertEquals(keys.contains(WRITTEN_KEY) ? ENTRIES + 1 : ENTRIES, keys.size(), "no entry may repeat");
		}

		@Test
		@DisplayName("lets any failure other than a concurrent modification through")
		void shouldPropagateAnyOtherFailure() {
			final int[] attempts = {0};

			assertThrows(
				IllegalStateException.class,
				() -> ManagementReads.<List<Integer>>walkTolerantly(
					() -> {
						attempts[0]++;
						return new ArrayList<>();
					},
					visited -> {
						throw new IllegalStateException("a genuine failure of the walk");
					}
				)
			);
			assertEquals(1, attempts[0], "a genuine failure must not be retried");
		}
	}

	/**
	 * A map key identified by its id alone, which runs `onHash` - when set - every time it is hashed. The callback
	 * never takes part in equality, so an armed key and an unarmed one with the same id are the same key.
	 *
	 * @param id     identity of the key
	 * @param onHash callback run on every `hashCode()` call, or null for an ordinary key
	 */
	private record CallbackKey(int id, @Nullable Runnable onHash) {

		@Override
		public boolean equals(Object o) {
			return o instanceof CallbackKey that && this.id == that.id;
		}

		@Override
		public int hashCode() {
			if (this.onHash != null) {
				this.onHash.run();
			}
			return Integer.hashCode(this.id);
		}
	}

}
