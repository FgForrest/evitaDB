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

package io.evitadb.core.query.filter.translator.fulltext;

import com.sun.management.ThreadMXBean;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.core.query.filter.translator.fulltext.FulltextExpansionTestSupport.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Direct correctness and allocation guards for the production bounded collector, without observation hooks. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Bounded fulltext term collector")
class FulltextTermHeapTest {
	/** Same capacity as the production collectors. */
	private static final int CAP = effectiveTermsPerLeg();
	/** Bounded selection independent of traversal order. */
	@Nested
	@DisplayName("Top term selection")
	class Selection {
		/**
		 * Adversarial ordering, distances, frequency ties and lexical ties have the same exact top terms up to the cap.
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps exactly the independent top terms up to the cap after thousands of offers in either order")
		void shouldKeepExactSurvivors(boolean reverse) {
			final FulltextTermHeap heap = new FulltextTermHeap(CAP);
			final Bitmap postings = new ArrayBitmap(1, 2);
			final ImpactView impacts = ImpactView.of(new byte[]{11, 17});
			final List<Expected> expected = new ArrayList<>();
			for (int n = 0; n < Math.max(4000, CAP * 2); n++) {
				final int i = reverse ? Math.max(4000, CAP * 2) - 1 - n : n;
				final String term = String.format(Locale.ROOT, "term%05d", i);
				final int distance = i % 3;
				final int frequency = i % 17;
				heap.offer(term, postings, impacts, distance, frequency);
				assertTrue(heap.size() <= CAP);
				expected.add(new Expected(term, distance, frequency));
			}
			expected.sort(Comparator.comparingInt(Expected::distance)
				.thenComparing(Comparator.comparingInt(Expected::frequency).reversed()).thenComparing(Expected::term));
			final TreeSet<String> terms = new TreeSet<>();
			for (int i = 0; i < heap.size(); i++) {
				final FulltextTermHeap.Entry entry = heap.get(i);
				terms.add(entry.term());
				assertSame(postings, entry.postings());
				assertSame(impacts, entry.impacts());
				assertTrue(expected.subList(0, CAP).contains(
					new Expected(entry.term(), entry.distance(), entry.frequency())));
			}
			final TreeSet<String> winners = new TreeSet<>();
			for (int i = 0; i < CAP; i++) {
				winners.add(expected.get(i).term());
			}
			assertEquals(CAP, heap.size());
			assertEquals(winners, terms);
		}

		/** Increasing frequencies force every later offer to replace the worst slot. */
		@Test
		@DisplayName("Reuses a slot with equal-cardinality disjoint postings and new aligned impacts")
		void shouldReplaceViews() {
			final FulltextTermHeap heap = new FulltextTermHeap(2);
			for (int i = 0; i < 1000; i++) {
				heap.offer("term" + i, new ArrayBitmap(2 * i + 1, 2 * i + 2),
					ImpactView.of(new byte[]{(byte) (i % 100), (byte) (100 + i % 100)}), 1, i + 1);
				assertTrue(heap.size() <= 2);
			}
			for (int slot = 0; slot < heap.size(); slot++) {
				final FulltextTermHeap.Entry entry = heap.get(slot);
				final int i = Integer.parseInt(entry.term().substring(4));
				assertTrue(i == 998 || i == 999);
				assertArrayEquals(new int[]{2 * i + 1, 2 * i + 2}, entry.postings().getArray());
				assertArrayEquals(new byte[]{(byte) (i % 100), (byte) (100 + i % 100)}, entry.impacts().toArray());
			}
		}

		/** A term hash collision cannot conflate different stems or disturb duplicate no-ops. */
		@Test
		@DisplayName("Keeps colliding term hashes distinct and leaves duplicate payloads unchanged")
		void shouldIgnoreDuplicatesAndHandleHashCollisions() {
			assertEquals("Aa".hashCode(), "BB".hashCode());
			final FulltextTermHeap heap = new FulltextTermHeap(2);
			final Bitmap original = new ArrayBitmap(1, 2);
			final ImpactView impacts = ImpactView.of(new byte[]{9, 31});
			heap.offer("Aa", original, impacts, 1, 2);
			heap.offer("BB", new ArrayBitmap(3, 4), ImpactView.of(new byte[]{8, 32}), 1, 2);
			heap.offer("Aa", new ArrayBitmap(5, 6), ImpactView.of(new byte[]{7, 33}), 1, 999);
			assertEquals(2, heap.size());
			final Map<String, FulltextTermHeap.Entry> entries = CollectionUtils.createHashMap(2);
			for (int i = 0; i < heap.size(); i++) {
				entries.put(heap.get(i).term(), heap.get(i));
			}
			assertEquals(Set.of("Aa", "BB"), entries.keySet());
			assertSame(original, entries.get("Aa").postings());
			assertSame(impacts, entries.get("Aa").impacts());
			assertEquals(2, entries.get("Aa").frequency());
			assertArrayEquals(new int[]{3, 4}, entries.get("BB").postings().getArray());
			assertArrayEquals(new byte[]{8, 32}, entries.get("BB").impacts().toArray());
			assertEquals(2, entries.get("BB").frequency());
		}
	}

	/** Overlapping variants and lane words may improve a retained stem's distance. */
	@Nested
	@DisplayName("Minimum duplicate distance")
	class Invariants {
		/** Both directions preserve the minimum and keep the original borrowed payload. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Keeps the smallest offered distance and repairs the heap before replacement")
		void shouldKeepSmallestDuplicateDistance(boolean reverse) {
			final FulltextTermHeap heap = new FulltextTermHeap(2);
			final Bitmap postings = new ArrayBitmap(1);
			final ImpactView impacts = ImpactView.of(new byte[]{7});
			heap.offer("improved", postings, impacts, reverse ? 1 : 2, 1);
			heap.offer("other", new ArrayBitmap(2), ImpactView.of(new byte[]{8}), 2, 2);
			assertEquals(!reverse, heap.canOffer(new StringBuilder("improved"), reverse ? 2 : 1, 1));
			heap.offer("improved", new ArrayBitmap(99), ImpactView.of(new byte[]{99}), reverse ? 2 : 1, 999);
			assertFalse(heap.canOffer("improved", 1, 1));
			heap.offer("winner", new ArrayBitmap(3), ImpactView.of(new byte[]{9}), 1, 2);
			assertEquals(2, heap.size());
			for (int i = 0; i < heap.size(); i++) {
				final FulltextTermHeap.Entry entry = heap.get(i);
				assertEquals(1, entry.distance());
				assertNotEquals("other", entry.term());
				if (entry.term().equals("improved")) {
					assertSame(postings, entry.postings());
					assertSame(impacts, entry.impacts());
				}
			}
		}
	}

	/** Argument guards of the collector and its slot access. */
	@Nested
	@DisplayName("Arguments")
	class Arguments {
		/** A collector that can retain nothing is a programming error. */
		@ParameterizedTest
		@ValueSource(ints = {0, -1})
		@DisplayName("Rejects a capacity below one")
		void shouldRejectNonPositiveCapacity(int capacity) {
			assertThrows(GenericEvitaInternalError.class, () -> new FulltextTermHeap(capacity));
		}

		/** Slots beyond the occupied ones are not readable, even within the capacity. */
		@Test
		@DisplayName("Refuses slots outside the occupied range")
		void shouldRejectUnoccupiedSlot() {
			final FulltextTermHeap heap = new FulltextTermHeap(4);
			heap.offer("term", new ArrayBitmap(1), ImpactView.of(new byte[]{1}), 1, 1);
			assertThrows(IndexOutOfBoundsException.class, () -> heap.get(1));
			assertThrows(IndexOutOfBoundsException.class, () -> heap.get(-1));
		}

		/** A single slot keeps the smallest distance, then the highest frequency, then the first term. */
		@Test
		@DisplayName("Keeps only the best offer in a single slot")
		void shouldKeepBestOfferInSingleSlot() {
			final Bitmap postings = new ArrayBitmap(1);
			final ImpactView impacts = ImpactView.of(new byte[]{1});
			final FulltextTermHeap heap = new FulltextTermHeap(1);
			heap.offer("delta", postings, impacts, 2, 100);
			heap.offer("gamma", postings, impacts, 1, 3);
			heap.offer("beta", postings, impacts, 1, 5);
			heap.offer("alpha", postings, impacts, 1, 5);
			heap.offer("zeta", postings, impacts, 1, 4);
			assertEquals(1, heap.size());
			assertEquals("alpha", heap.get(0).term());
			assertEquals(5, heap.get(0).frequency());
		}
	}

	/** Allocation correctness with prebuilt input; no timing or throughput measurements. */
	@Nested
	@DisplayName("Replacement allocation")
	class Allocation {
		/** Warms slots and inputs first, then checks that a replacement reuses the worst slot without allocating. */
		@Test
		@DisplayName("Does not allocate a term-index node for every replacement")
		void shouldNotAllocateOnReplacement() {
			final java.lang.management.ThreadMXBean platform = ManagementFactory.getThreadMXBean();
			assumeTrue(platform instanceof ThreadMXBean);
			final ThreadMXBean threads = (ThreadMXBean) platform;
			assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
			final String[] terms = new String[12000];
			for (int i = 0; i < terms.length; i++) {
				terms[i] = String.format(Locale.ROOT, "term%05d", i);
				terms[i].hashCode();
			}
			final FulltextTermHeap heap = new FulltextTermHeap(CAP);
			final Bitmap postings = new ArrayBitmap(1, 2);
			final ImpactView impacts = ImpactView.of(new byte[]{3, 11});
			for (int i = 0; i < 2000; i++) {
				heap.offer(terms[i], postings, impacts, 1, i + 1);
			}
			final long thread = Thread.currentThread().threadId();
			final long before = threads.getThreadAllocatedBytes(thread);
			for (int i = 2000; i < terms.length; i++) {
				heap.offer(terms[i], postings, impacts, 1, i + 1);
			}
			final long allocated = threads.getThreadAllocatedBytes(thread) - before;
			assertEquals(CAP, heap.size());
			assertTrue(allocated < 8192, "Replacement allocation bytes: " + allocated);
		}
	}

	/** Independent expected ranking tuple, deliberately separate from the production comparator. */
	private record Expected(String term, int distance, int frequency) { }
}
