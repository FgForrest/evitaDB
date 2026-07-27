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

package io.evitadb.index.mutation;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.AffectedEntityResolution;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.AffectedReferenceEntry;
import io.evitadb.index.mutation.ReevaluateExpressionExecutor.AffectedReferenceGroup;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Tests for the supporting records inside {@link ReevaluateExpressionExecutor}: {@link AffectedEntityResolution},
 * {@link AffectedReferenceGroup}, and {@link AffectedReferenceEntry}. Verifies bitmap union computation, lazy filtered
 * entry iteration, and record field access.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AffectedEntityResolution supporting records")
@Tag(INDEXING)
@Tag(SCHEMA)
class AffectedEntityResolutionTest implements TimeBoundedTestSupport {

	/**
	 * Tests for {@link AffectedEntityResolution#allOwnerPKs()} verifying correct bitmap union behaviour across
	 * empty, single-group, and multi-group resolutions.
	 */
	@Nested
	@DisplayName("allOwnerPKs() union bitmap")
	class AllOwnerPKsTest {

		@Test
		@DisplayName("Should return empty bitmap for EMPTY resolution")
		void shouldReturnEmptyBitmapForEmptyResolution() {
			final Bitmap result = AffectedEntityResolution.EMPTY.allOwnerPKs();

			assertTrue(result.isEmpty(), "EMPTY resolution must yield an empty bitmap");
			assertEquals(0, result.size());
		}

		@Test
		@DisplayName("Should return owner PKs directly for single group")
		void shouldReturnOwnerPKsForSingleGroup() {
			final BaseBitmap ownerPKs = new BaseBitmap(10, 20, 30);
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(new AffectedReferenceGroup(1, 100, ownerPKs))
			);

			final Bitmap result = resolution.allOwnerPKs();

			assertEquals(3, result.size());
			assertTrue(result.contains(10));
			assertTrue(result.contains(20));
			assertTrue(result.contains(30));
		}

		@Test
		@DisplayName("Should return union for multiple groups with distinct PKs")
		void shouldReturnUnionForMultipleGroups() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(
					new AffectedReferenceGroup(1, 100, new BaseBitmap(1, 2)),
					new AffectedReferenceGroup(2, 200, new BaseBitmap(3, 4))
				)
			);

			final Bitmap result = resolution.allOwnerPKs();

			assertEquals(4, result.size());
			assertArrayEquals(new int[]{1, 2, 3, 4}, result.getArray());
		}

		@Test
		@DisplayName("Should deduplicate overlapping PKs in union")
		void shouldDeduplicateOverlappingPKsInUnion() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(
					new AffectedReferenceGroup(1, 100, new BaseBitmap(1, 2, 3)),
					new AffectedReferenceGroup(2, 200, new BaseBitmap(2, 3, 4))
				)
			);

			final Bitmap result = resolution.allOwnerPKs();

			assertEquals(4, result.size(), "Overlapping PKs 2 and 3 must appear only once in the union");
			assertArrayEquals(new int[]{1, 2, 3, 4}, result.getArray());
		}

		@Test
		@DisplayName("Should handle group with empty bitmap alongside non-empty group")
		void shouldHandleGroupWithEmptyBitmap() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(
					new AffectedReferenceGroup(1, 100, new BaseBitmap(5, 10)),
					new AffectedReferenceGroup(2, 200, new BaseBitmap())
				)
			);

			final Bitmap result = resolution.allOwnerPKs();

			assertEquals(2, result.size());
			assertArrayEquals(new int[]{5, 10}, result.getArray());
		}
	}

	/**
	 * Tests for {@link AffectedEntityResolution#entriesForOwnerPKs(Bitmap)} verifying correct lazy filtering
	 * of entries against the provided owner PK bitmap.
	 */
	@Nested
	@DisplayName("entriesForOwnerPKs() filtering")
	class EntriesForOwnerPKsTest {

		@Test
		@DisplayName("Should return all entries when filter matches all owner PKs")
		void shouldReturnAllEntriesWhenFilterMatchesAll() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(new AffectedReferenceGroup(1, 100, new BaseBitmap(10, 20, 30)))
			);

			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(10, 20, 30))
			);

			assertEquals(3, entries.size());
			assertEquals(new AffectedReferenceEntry(1, 100, 10), entries.get(0));
			assertEquals(new AffectedReferenceEntry(1, 100, 20), entries.get(1));
			assertEquals(new AffectedReferenceEntry(1, 100, 30), entries.get(2));
		}

		@Test
		@DisplayName("Should return subset when filter is partial")
		void shouldReturnSubsetWhenFilterIsPartial() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(new AffectedReferenceGroup(1, 100, new BaseBitmap(10, 20, 30)))
			);

			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(20))
			);

			assertEquals(1, entries.size());
			assertEquals(new AffectedReferenceEntry(1, 100, 20), entries.get(0));
		}

		@Test
		@DisplayName("Should return empty when filter has no overlap")
		void shouldReturnEmptyWhenFilterHasNoOverlap() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(new AffectedReferenceGroup(1, 100, new BaseBitmap(10, 20)))
			);

			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(99, 100))
			);

			assertTrue(entries.isEmpty(), "No entries should be returned when filter has no overlap");
		}

		@Test
		@DisplayName("Should return empty for EMPTY resolution")
		void shouldReturnEmptyForEmptyResolution() {
			final List<AffectedReferenceEntry> entries = collectEntries(
				AffectedEntityResolution.EMPTY.entriesForOwnerPKs(new BaseBitmap(1, 2, 3))
			);

			assertTrue(entries.isEmpty(), "EMPTY resolution must yield no entries");
		}

		@Test
		@DisplayName("Should return empty when filter bitmap is empty")
		void shouldReturnEmptyWhenFilterBitmapIsEmpty() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(new AffectedReferenceGroup(1, 100, new BaseBitmap(10, 20)))
			);

			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap())
			);

			assertTrue(entries.isEmpty(), "Empty filter bitmap must yield no entries");
		}

		@Test
		@DisplayName("Should fan out across multiple groups")
		void shouldFanOutAcrossMultipleGroups() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(
					new AffectedReferenceGroup(1, 100, new BaseBitmap(10, 20, 30)),
					new AffectedReferenceGroup(2, 200, new BaseBitmap(20, 40, 50))
				)
			);
			// filter matches PK 20 from both groups and PK 40 from second group only
			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(20, 40))
			);

			assertEquals(3, entries.size());
			assertEquals(new AffectedReferenceEntry(1, 100, 20), entries.get(0));
			assertEquals(new AffectedReferenceEntry(2, 200, 20), entries.get(1));
			assertEquals(new AffectedReferenceEntry(2, 200, 40), entries.get(2));
		}

		/**
		 * Verifies that calling `next()` on the iterator returned by `entriesForOwnerPKs()` after
		 * exhaustion throws {@link NoSuchElementException}.
		 */
		@Test
		@DisplayName("Should throw NoSuchElementException when next() called on exhausted iterator")
		void shouldThrowNoSuchElementExceptionOnExhaustedIterator() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(new AffectedReferenceGroup(1, 100, new BaseBitmap(10)))
			);

			final Iterator<AffectedReferenceEntry> iterator =
				resolution.entriesForOwnerPKs(new BaseBitmap(10)).iterator();

			// drain the single entry
			assertTrue(iterator.hasNext());
			iterator.next();

			// now exhausted
			assertFalse(iterator.hasNext());
			assertThrows(NoSuchElementException.class, iterator::next);
		}

		@Test
		@DisplayName("Should preserve facetPK, groupPK, and ownerPK in yielded entries")
		void shouldPreserveFieldsInEntries() {
			final AffectedEntityResolution resolution = new AffectedEntityResolution(
				List.of(
					new AffectedReferenceGroup(42, null, new BaseBitmap(7)),
					new AffectedReferenceGroup(99, 500, new BaseBitmap(7))
				)
			);

			final List<AffectedReferenceEntry> entries = collectEntries(
				resolution.entriesForOwnerPKs(new BaseBitmap(7))
			);

			assertEquals(2, entries.size());

			final AffectedReferenceEntry ungrouped = entries.get(0);
			assertEquals(42, ungrouped.referencedEntityPK());
			assertNull(ungrouped.groupPK(), "Ungrouped facet must have null groupPK");
			assertEquals(7, ungrouped.ownerPK());

			final AffectedReferenceEntry grouped = entries.get(1);
			assertEquals(99, grouped.referencedEntityPK());
			assertEquals(500, grouped.groupPK());
			assertEquals(7, grouped.ownerPK());
		}
	}

	/**
	 * Collects all entries from the given iterable into a list for assertion.
	 *
	 * @param iterable the iterable to drain
	 * @return list of all entries yielded by the iterable
	 */
	@Nonnull
	private static List<AffectedReferenceEntry> collectEntries(
		@Nonnull Iterable<AffectedReferenceEntry> iterable
	) {
		final List<AffectedReferenceEntry> result = new ArrayList<>(8);
		for (AffectedReferenceEntry entry : iterable) {
			result.add(entry);
		}
		return result;
	}

}
