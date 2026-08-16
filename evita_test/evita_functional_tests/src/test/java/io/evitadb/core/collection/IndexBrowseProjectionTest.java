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

package io.evitadb.core.collection;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexActivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the browse projection against a hand-built index map, which is what lets it pin the cases a catalog fixture
 * cannot reach cheaply: exact entity counts chosen per index, and representative attribute values crafted to collide.
 *
 * `IndexBrowseTest` covers the same surface through a real engine and is the better place for anything about how
 * indexes come to exist. This class exists for the arithmetic and the rendering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see IndexBrowseProjection
 */
@DisplayName("Index browse projection")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class IndexBrowseProjectionTest {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "categories";
	private static final long CATALOG_VERSION = 17L;
	/** An arbitrary but recognisable instant, and two later ones, so a stamp read off the wrong holder is visible. */
	private static final long FIRST_MILLIS = 1_800_000_000_000L;
	private static final long SECOND_MILLIS = 1_800_000_060_000L;
	private static final long THIRD_MILLIS = 1_800_000_120_000L;
	/** Hands out a distinct primary key per fixture index - the identity every descriptor is addressed by. */
	private static final AtomicInteger INDEX_PRIMARY_KEYS = new AtomicInteger();

	@Nested
	@DisplayName("Discriminator rendering")
	class DiscriminatorRendering {

		@Test
		@DisplayName("Representative values that would collide when joined are still told apart")
		void shouldNotCollapseRepresentativeValuesThatShareAJoinedRendering() {
			// both key sets join to the same text under a naive separator-joined rendering: "a, b, c". They are
			// genuinely different indexes, and the descriptor's whole identity contract rests on saying so
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				indexEntry(representativeKey(5, "a", "b, c"), 3),
				indexEntry(representativeKey(5, "a, b", "c"), 3)
			);

			final List<String> discriminators = discriminatorsOf(browseAll(indexes));

			assertEquals(2, discriminators.size(), "Both indexes must be returned");
			assertNotEquals(
				discriminators.get(0), discriminators.get(1),
				"Two indexes differing only in how their representative values split must not report one identity - " +
					"a client deduplicating on the discriminator would otherwise silently drop one of them"
			);
		}

		@Test
		@DisplayName("An absent representative value is told apart from the text that denotes it")
		void shouldNotCollapseAnAbsentValueWithItsOwnPlaceholderText() {
			// the other collision a joined rendering admits: a null element printed as the literal `NULL` is
			// indistinguishable from a value that genuinely is the string "NULL"
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				indexEntry(representativeKey(7, new Serializable[]{null}), 1),
				indexEntry(representativeKey(7, "NULL"), 1)
			);

			final List<String> discriminators = discriminatorsOf(browseAll(indexes));

			assertEquals(2, discriminators.size());
			assertNotEquals(discriminators.get(0), discriminators.get(1));
		}

		@Test
		@DisplayName("An index bound to no reference reports no discriminator")
		void shouldReportNoDiscriminatorForAGlobalIndex() {
			final IndexBrowseResult result = browseAll(mapOf(globalIndexEntry(Scope.LIVE, 4)));

			final BrowsedIndex index = result.indexes()[0];
			assertEquals(EntityIndexType.GLOBAL, index.indexType());
			assertNull(index.discriminator(), "A global index has no siblings to be told apart from");
			assertNull(index.referenceName());
			assertNull(index.discriminatorPrimaryKey());
		}
	}

	@Nested
	@DisplayName("Page boundaries")
	class PageBoundaries {

		@Test
		@DisplayName("A size-ordered page past the last match is empty rather than an error")
		void shouldReturnAnEmptyPageWhenTheSizeOrderedOffsetIsPastTheLastMatch() {
			// exercises the clamp in the page cut. Without it the page window is computed from an offset larger than
			// the retained set and the resulting negative capacity throws instead of returning nothing
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				indexEntry(representativeKey(1), 30),
				indexEntry(representativeKey(2), 20),
				indexEntry(representativeKey(3), 10)
			);

			final IndexBrowseResult result = browse(
				indexes, new IndexBrowseCriteria(
					20, 5, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC,
					EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
				)
			);

			assertEquals(0, result.indexes().length, "A page past the end holds no indexes");
			assertEquals(3, result.totalRecordCount(), "The match count describes the whole result, not the page");
		}

		@Test
		@DisplayName("The last page holds the remainder when the matches do not fill it")
		void shouldReturnAShortFinalPage() {
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				indexEntry(representativeKey(1), 30),
				indexEntry(representativeKey(2), 20),
				indexEntry(representativeKey(3), 10)
			);

			final IndexBrowseResult result = browse(
				indexes, new IndexBrowseCriteria(
					2, 2, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC,
					EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
				)
			);

			assertEquals(1, result.indexes().length, "Three matches over pages of two leave one on the second page");
			assertEquals(10, result.indexes()[0].entityCount(), "The remainder is the smallest index");
		}
	}

	@Nested
	@DisplayName("Ordering")
	class Ordering {

		@Test
		@DisplayName("Only the largest indexes survive once the retention heap starts evicting")
		void shouldRetainExactlyTheLargestIndexesWhenTheHeapEvicts() {
			// five matches into a window of two, so the eviction arm runs three times - the branch a page wide enough
			// to hold everything never reaches
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				indexEntry(representativeKey(1), 10),
				indexEntry(representativeKey(2), 50),
				indexEntry(representativeKey(3), 20),
				indexEntry(representativeKey(4), 40),
				indexEntry(representativeKey(5), 30)
			);

			final IndexBrowseResult result = browse(
				indexes, new IndexBrowseCriteria(
					1, 2, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC,
					EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
				)
			);

			assertEquals(2, result.indexes().length);
			assertEquals(50, result.indexes()[0].entityCount(), "The largest index must lead");
			assertEquals(40, result.indexes()[1].entityCount(), "The second largest must follow it");
			assertEquals(5, result.totalRecordCount(), "Eviction must not change how many matched");
		}

		@Test
		@DisplayName("Indexes of equal size are evicted by the tiebreaker, not by arrival order")
		void shouldEvictAgainstTheTieBreakerWhenEveryCountIsEqual() {
			// every count identical, so retention is decided purely by the key ordering the tiebreaker imposes
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				indexEntry(representativeKey(9), 7),
				indexEntry(representativeKey(3), 7),
				indexEntry(representativeKey(6), 7)
			);

			final IndexBrowseResult result = browse(
				indexes, new IndexBrowseCriteria(
					1, 2, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC,
					EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
				)
			);

			assertEquals(2, result.indexes().length);
			assertEquals(3, result.indexes()[0].discriminatorPrimaryKey(), "Ties resolve by discriminator, ascending");
			assertEquals(6, result.indexes()[1].discriminatorPrimaryKey());
		}
	}

	@Nested
	@DisplayName("Empty input")
	class EmptyInput {

		@Test
		@DisplayName("An empty index map yields an empty page and echoes the request back")
		void shouldReturnAnEmptyResultForAnEmptyIndexMap() {
			for (final IndexBrowseOrdering ordering : IndexBrowseOrdering.values()) {
				final IndexBrowseResult result = browse(
					Map.of(), new IndexBrowseCriteria(
						1, 10, ordering, EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
					)
				);

				assertEquals(0, result.totalRecordCount(), "Nothing matches an empty map, in " + ordering);
				assertEquals(0, result.indexes().length, "The page is empty, in " + ordering);
				assertEquals(1, result.pageNumber(), "The request is echoed back, in " + ordering);
				assertEquals(10, result.pageSize(), "The request is echoed back, in " + ordering);
				assertEquals(CATALOG_VERSION, result.catalogVersion(), "The version is reported, in " + ordering);
			}
		}
	}

	@Nested
	@DisplayName("Index identity")
	class IndexIdentity {

		/**
		 * The two orderings carry the identity by different routes - one reads it off the index it just fetched, the
		 * other retains it in a heap candidate across the whole walk - so a page that lost or transposed it would show
		 * up in only one of them. Both are therefore asserted against the same fixture.
		 */
		@Test
		@DisplayName("Every descriptor reports the primary key of the index it describes, in either ordering")
		void shouldReportTheIndexPrimaryKeyOfEachDescribedIndex() {
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(
				globalIndexEntry(Scope.LIVE, 50),
				indexEntry(representativeKey(5, "a"), 30),
				indexEntry(representativeKey(6, "b"), 10)
			);
			final Set<Integer> fixturePrimaryKeys = new HashSet<>(indexes.size());
			for (final EntityIndex index : indexes.values()) {
				fixturePrimaryKeys.add(index.getPrimaryKey());
			}

			for (final IndexBrowseOrdering ordering : IndexBrowseOrdering.values()) {
				final IndexBrowseResult result = browse(
					indexes, new IndexBrowseCriteria(
						1, IndexBrowseCriteria.MAX_PAGE_SIZE, ordering,
						EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
					)
				);

				assertEquals(indexes.size(), result.indexes().length, "Every index is on the page, in " + ordering);
				final Set<Integer> reported = new HashSet<>(result.indexes().length);
				for (final BrowsedIndex described : result.indexes()) {
					assertTrue(
						reported.add(described.indexPrimaryKey()),
						"Two descriptors reported one identity, in " + ordering + " - a client drilling into either " +
							"would reach the same index and never see the other"
					);
					assertTrue(
						fixturePrimaryKeys.contains(described.indexPrimaryKey()),
						"Descriptor reported primary key " + described.indexPrimaryKey() +
							", which belongs to no index of the fixture, in " + ordering
					);
				}
			}
		}

		@Test
		@DisplayName("The identity travels with the row it belongs to, not with its position")
		void shouldPairEachIdentityWithItsOwnEntityCount() {
			// the failure this catches is a transposition: the heap orders candidates by entity count, so a page that
			// paired identities with positions rather than with rows would still report the right set of both
			final Map.Entry<EntityIndexKey, EntityIndex> largest = indexEntry(representativeKey(5, "a"), 50);
			final Map.Entry<EntityIndexKey, EntityIndex> smallest = indexEntry(representativeKey(6, "b"), 10);
			// inserted smallest-first, so map order and size order disagree
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(smallest, largest);

			final IndexBrowseResult result = browse(
				indexes, new IndexBrowseCriteria(
					1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.BY_ENTITY_COUNT_DESC,
					EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
				)
			);

			assertEquals(50, result.indexes()[0].entityCount(), "The largest index leads a size-ordered page");
			assertEquals(
				largest.getValue().getPrimaryKey(), result.indexes()[0].indexPrimaryKey(),
				"The leading row must identify the index it reports the count of"
			);
			assertEquals(
				smallest.getValue().getPrimaryKey(), result.indexes()[1].indexPrimaryKey(),
				"The trailing row must identify the index it reports the count of"
			);
		}
	}

	@Nested
	@DisplayName("Activity readings")
	class ActivityReadings {

		/**
		 * The two orderings reach the activity holder by different routes - the map-order walk reads it off the index
		 * it has just fetched, while the size-ordered one retains it on a heap candidate for the whole walk and reads
		 * it only when the page is cut. A page that dropped the holder, or paired it with the wrong row, would
		 * therefore show up in one ordering and not in the other, so both are asserted against the same fixture.
		 */
		@Test
		@DisplayName("Every row reports the traffic of the index it describes, in either ordering")
		void shouldReportTheActivityOfEachDescribedIndex() {
			// both indexes cover the same number of entities, so the only thing telling their rows apart is the traffic
			// one of them saw - a projection reading a fresh or a neighbouring holder cannot come out right by chance
			final Map.Entry<EntityIndexKey, EntityIndex> busy = indexEntry(representativeKey(5, "a"), 20);
			final Map.Entry<EntityIndexKey, EntityIndex> idle = indexEntry(representativeKey(6, "b"), 20);
			final Map<EntityIndexKey, EntityIndex> indexes = mapOf(busy, idle);
			final IndexActivity activity = busy.getValue().getActivity();
			activity.recordQuery(FIRST_MILLIS);
			activity.recordQuery(SECOND_MILLIS);
			activity.recordUpdate(FIRST_MILLIS);
			activity.recordUpdate(SECOND_MILLIS);
			activity.recordUpdate(THIRD_MILLIS);

			for (final IndexBrowseOrdering ordering : IndexBrowseOrdering.values()) {
				final IndexBrowseResult result = browse(
					indexes, new IndexBrowseCriteria(
						1, IndexBrowseCriteria.MAX_PAGE_SIZE, ordering,
						EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
					)
				);

				final BrowsedIndex busyRow = rowOf(result, busy.getValue().getPrimaryKey());
				assertEquals(2L, busyRow.queryCount(), "The query count belongs to this row, in " + ordering);
				assertEquals(3L, busyRow.updateCount(), "The update count belongs to this row, in " + ordering);
				assertEquals(toTimestamp(SECOND_MILLIS), busyRow.lastQueriedAt(), "in " + ordering);
				assertEquals(toTimestamp(THIRD_MILLIS), busyRow.lastUpdatedAt(), "in " + ordering);

				final BrowsedIndex idleRow = rowOf(result, idle.getValue().getPrimaryKey());
				assertEquals(0L, idleRow.queryCount(), "An index nothing queried reported traffic, in " + ordering);
				assertEquals(0L, idleRow.updateCount(), "An index nothing wrote reported traffic, in " + ordering);
				// absence rather than the epoch, which a client would render as a date in 1970
				assertNull(idleRow.lastQueriedAt(), "in " + ordering);
				assertNull(idleRow.lastUpdatedAt(), "in " + ordering);
				assertTrue(idleRow.lastQueriedAtIfKnown().isEmpty(), "in " + ordering);
				assertTrue(idleRow.lastUpdatedAtIfKnown().isEmpty(), "in " + ordering);
			}
		}

	}

	/**
	 * Picks the row describing one index out of a page.
	 *
	 * @param result          the page to read
	 * @param indexPrimaryKey identity of the index whose row is wanted
	 * @return that index's row
	 */
	@Nonnull
	private static BrowsedIndex rowOf(@Nonnull IndexBrowseResult result, int indexPrimaryKey) {
		for (final BrowsedIndex index : result.indexes()) {
			if (index.indexPrimaryKey() == indexPrimaryKey) {
				return index;
			}
		}
		throw new AssertionError("No row describes the index with primary key " + indexPrimaryKey);
	}

	/**
	 * Renders epoch millis the way {@link IndexActivity} does, so an assertion compares like with like rather than
	 * restating the conversion.
	 *
	 * @param millis the stamp to render
	 * @return the timestamp in the JVM's own zone
	 */
	@Nonnull
	private static OffsetDateTime toTimestamp(long millis) {
		return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
	}

	/**
	 * Browses every index of the map, unfiltered, in map order.
	 *
	 * @param indexes the map to browse
	 * @return the single page holding all of them
	 */
	@Nonnull
	private static IndexBrowseResult browseAll(@Nonnull Map<EntityIndexKey, EntityIndex> indexes) {
		return browse(
			indexes, new IndexBrowseCriteria(
				1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER,
				EnumSet.noneOf(EntityIndexType.class), Set.of(), Set.of()
			)
		);
	}

	/**
	 * Runs the projection under test.
	 *
	 * @param indexes  the map to browse
	 * @param criteria what to select, in what order, and which page
	 * @return the resulting page
	 */
	@Nonnull
	private static IndexBrowseResult browse(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria
	) {
		return IndexBrowseProjection.browse(ENTITY_TYPE, indexes, criteria, CATALOG_VERSION);
	}

	/**
	 * Collects the discriminators of a page, in the order it returned them.
	 *
	 * @param result the page to read
	 * @return its discriminators
	 */
	@Nonnull
	private static List<String> discriminatorsOf(@Nonnull IndexBrowseResult result) {
		final List<String> discriminators = new ArrayList<>(result.indexes().length);
		for (final BrowsedIndex index : result.indexes()) {
			discriminators.add(index.discriminator());
		}
		return discriminators;
	}

	/**
	 * Builds an index map preserving insertion order, so a map-order walk is predictable.
	 *
	 * @param entries the entries to hold
	 * @return the map
	 */
	@SafeVarargs
	@Nonnull
	private static Map<EntityIndexKey, EntityIndex> mapOf(@Nonnull Map.Entry<EntityIndexKey, EntityIndex>... entries) {
		final Map<EntityIndexKey, EntityIndex> indexes = new LinkedHashMap<>(entries.length);
		for (final Map.Entry<EntityIndexKey, EntityIndex> entry : entries) {
			indexes.put(entry.getKey(), entry.getValue());
		}
		return indexes;
	}

	/**
	 * Builds one per-referenced-entity index covering the requested number of entities.
	 *
	 * @param discriminator key distinguishing this index from its siblings
	 * @param entityCount   how many entities it should cover
	 * @return the map entry
	 */
	@Nonnull
	private static Map.Entry<EntityIndexKey, EntityIndex> indexEntry(
		@Nonnull RepresentativeReferenceKey discriminator,
		int entityCount
	) {
		final EntityIndexKey key = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, discriminator
		);
		return Map.entry(key, populated(key, entityCount));
	}

	/**
	 * Builds the collection-wide index covering the requested number of entities.
	 *
	 * @param scope       scope the index belongs to
	 * @param entityCount how many entities it should cover
	 * @return the map entry
	 */
	@Nonnull
	private static Map.Entry<EntityIndexKey, EntityIndex> globalIndexEntry(@Nonnull Scope scope, int entityCount) {
		final EntityIndexKey key = new EntityIndexKey(EntityIndexType.GLOBAL, scope);
		return Map.entry(key, populated(key, entityCount));
	}

	/**
	 * Builds an index holding the requested number of entities. The projection reads the key, the primary-key bitmap
	 * and the index's own primary key, so the concrete index class is irrelevant to what is being asserted.
	 *
	 * @param key         key identifying the index
	 * @param entityCount how many entities to insert
	 * @return the populated index
	 */
	@Nonnull
	private static EntityIndex populated(@Nonnull EntityIndexKey key, int entityCount) {
		// each fixture index gets its own primary key, so a descriptor carrying the wrong one is visible rather than
		// coincidentally right
		final GlobalEntityIndex index = new GlobalEntityIndex(INDEX_PRIMARY_KEYS.incrementAndGet(), ENTITY_TYPE, key);
		for (int entityPrimaryKey = 1; entityPrimaryKey <= entityCount; entityPrimaryKey++) {
			index.insertPrimaryKeyIfMissing(entityPrimaryKey);
		}
		assertEquals(entityCount, index.getAllPrimaryKeys().size(), "Fixture must hold what the test asked for");
		return index;
	}

	/**
	 * Builds a discriminator for one target of the fixture's reference.
	 *
	 * @param targetPrimaryKey      primary key of the referenced entity
	 * @param representativeValues values distinguishing this reference from its duplicates
	 * @return the discriminator
	 */
	@Nonnull
	private static RepresentativeReferenceKey representativeKey(
		int targetPrimaryKey,
		@Nonnull Serializable... representativeValues
	) {
		return new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, targetPrimaryKey), representativeValues
		);
	}

}
