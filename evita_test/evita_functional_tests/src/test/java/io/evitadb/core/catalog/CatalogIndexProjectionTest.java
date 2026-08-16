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

package io.evitadb.core.catalog;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the catalog's own indexes browse and drill down exactly as an entity collection's do - the unification
 * this projection exists for - and that the three ways they *cannot* is stated by absence rather than by a stand-in.
 *
 * Two things here are worth more than the row-by-row assertions. The first is that
 * {@link CatalogIndexProjection#toIndexPrimaryKey} is a **published wire contract**: the numbers travel to clients and
 * come back, so `shouldDeriveTheSameHandlesForever` pins them literally - a change that renumbers them is a change a
 * client cannot see coming. The second is the filter semantics: an index-type or reference-name filter selects *no*
 * catalog index, which has to be an empty page rather than an error, because a catalog browse has no entity schema to
 * validate a reference name against.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(MANAGEMENT)
@DisplayName("Catalog index projection")
class CatalogIndexProjectionTest {

	private static final String ENTITY_TYPE = "Product";
	private static final long CATALOG_VERSION = 17L;
	/** An arbitrary but recognisable instant, and two later ones, so a stamp read off the wrong holder is visible. */
	private static final long FIRST_MILLIS = 1_800_000_000_000L;
	private static final long SECOND_MILLIS = 1_800_000_060_000L;
	private static final long THIRD_MILLIS = 1_800_000_120_000L;

	private static final EntityTypeClassifierResolver RESOLVER = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return 1;
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return ENTITY_TYPE;
		}
	};

	@Nested
	@DisplayName("handle")
	class Handles {

		@Test
		@DisplayName("derives the same handles forever, and they are not the enum's ordinals by accident")
		void shouldDeriveTheSameHandlesForever() {
			// pinned literally because these numbers are handed to clients and handed back. They must never be
			// renumbered - only extended - and writing them out here is what makes a renumbering fail loudly rather
			// than silently re-point every handle a client is holding
			assertEquals(0, CatalogIndexProjection.toIndexPrimaryKey(Scope.LIVE));
			assertEquals(1, CatalogIndexProjection.toIndexPrimaryKey(Scope.ARCHIVED));
		}

		@Test
		@DisplayName("resolves every handle it hands out")
		void shouldResolveEveryHandleItHandsOut() {
			for (final Scope scope : Scope.values()) {
				assertEquals(scope, CatalogIndexProjection.toScope(CatalogIndexProjection.toIndexPrimaryKey(scope)));
			}
		}

		@Test
		@DisplayName("answers a handle that addresses no scope with null rather than a guess")
		void shouldNotResolveAHandleThatAddressesNoScope() {
			assertNull(CatalogIndexProjection.toScope(-1));
			assertNull(CatalogIndexProjection.toScope(Scope.values().length));
			assertNull(CatalogIndexProjection.toScope(Integer.MAX_VALUE));
		}

	}

	@Nested
	@DisplayName("browse")
	class Browse {

		@Test
		@DisplayName("renders every existing catalog index, stating by absence what it does not have")
		void shouldRenderEveryExistingCatalogIndex() {
			final IndexBrowseResult result = browse(
				List.of(empty(Scope.LIVE), empty(Scope.ARCHIVED)), criteria(1, 10)
			);

			assertEquals(CATALOG_VERSION, result.catalogVersion());
			assertEquals(2, result.totalRecordCount());
			assertEquals(2, result.indexes().length);
			for (final BrowsedIndex index : result.indexes()) {
				// the four absences are the index's shape, not readings that could not be taken - a catalog index
				// belongs to no collection, has no entity-index kind, is bound to no reference and maintains no
				// primary-key bitmap to count entities off
				assertNull(index.entityType());
				assertNull(index.indexType());
				assertNull(index.referenceName());
				assertNull(index.discriminator());
				assertNull(index.discriminatorPrimaryKey());
				assertNull(index.entityCount());
				assertTrue(index.entityCountIfKnown().isEmpty());
				assertNotNull(index.scope());
				// nothing has been recorded on either holder, so both counters read zero and both stamps state absence
				// rather than the epoch, which a client would render as a date in 1970
				assertEquals(0L, index.queryCount());
				assertEquals(0L, index.updateCount());
				assertNull(index.lastQueriedAt());
				assertNull(index.lastUpdatedAt());
				assertTrue(index.lastQueriedAtIfKnown().isEmpty());
				assertTrue(index.lastUpdatedAtIfKnown().isEmpty());
			}
			assertEquals(
				List.of(Scope.LIVE, Scope.ARCHIVED),
				Arrays.stream(result.indexes()).map(BrowsedIndex::scope).toList()
			);
		}

		@Test
		@DisplayName("reports only the scopes whose index has actually been created")
		void shouldReportOnlyTheScopesThatExist() {
			// the archived index is created lazily, so a catalog that has archived nothing has exactly one
			final IndexBrowseResult result = browse(List.of(empty(Scope.LIVE)), criteria(1, 10));
			assertEquals(1, result.totalRecordCount());
			assertEquals(Scope.LIVE, result.indexes()[0].scope());
			assertEquals(0, result.indexes()[0].indexPrimaryKey());
		}

		@Test
		@DisplayName("selects nothing when an entity index kind is named")
		void shouldSelectNothingWhenAKindIsNamed() {
			// naming any kind selects no catalog index, because none of them has one. An empty page is the accurate
			// answer to "show me the GLOBAL indexes the catalog holds itself"
			final IndexBrowseResult result = browse(
				List.of(empty(Scope.LIVE), empty(Scope.ARCHIVED)),
				new IndexBrowseCriteria(
					1, 10, IndexBrowseOrdering.MAP_ORDER,
					Set.of(EntityIndexType.GLOBAL), Set.of(), Set.of()
				)
			);
			assertEquals(0, result.totalRecordCount());
			assertEquals(0, result.indexes().length);
		}

		@Test
		@DisplayName("selects nothing when a reference is named, and does not reject the name")
		void shouldSelectNothingWhenAReferenceIsNamed() {
			// a collection browse rejects a reference its entity schema does not declare, so that a typo cannot read
			// as "this reference has no indexes". There is no entity schema here and no reference dimension at all,
			// so there is no typo to protect anyone from - the name is simply unsatisfiable
			final IndexBrowseResult result = browse(
				List.of(empty(Scope.LIVE)),
				new IndexBrowseCriteria(
					1, 10, IndexBrowseOrdering.MAP_ORDER, Set.of(), Set.of(), Set.of("categoriez")
				)
			);
			assertEquals(0, result.totalRecordCount());
			assertEquals(0, result.indexes().length);
		}

		@Test
		@DisplayName("keeps only the named scopes")
		void shouldKeepOnlyTheNamedScopes() {
			final IndexBrowseResult result = browse(
				List.of(empty(Scope.LIVE), empty(Scope.ARCHIVED)),
				new IndexBrowseCriteria(
					1, 10, IndexBrowseOrdering.MAP_ORDER, Set.of(), Set.of(Scope.ARCHIVED), Set.of()
				)
			);
			assertEquals(1, result.totalRecordCount());
			assertEquals(Scope.ARCHIVED, result.indexes()[0].scope());
			assertEquals(1, result.indexes()[0].indexPrimaryKey());
		}

		@Test
		@DisplayName("pages, and answers a page past the end with nothing rather than an error")
		void shouldPage() {
			final List<CatalogIndex> indexes = List.of(empty(Scope.LIVE), empty(Scope.ARCHIVED));

			final IndexBrowseResult first = browse(indexes, criteria(1, 1));
			assertEquals(2, first.totalRecordCount());
			assertEquals(1, first.indexes().length);
			assertEquals(Scope.LIVE, first.indexes()[0].scope());

			final IndexBrowseResult second = browse(indexes, criteria(2, 1));
			assertEquals(2, second.totalRecordCount());
			assertEquals(Scope.ARCHIVED, second.indexes()[0].scope());

			final IndexBrowseResult past = browse(indexes, criteria(9, 1));
			// the match count still reports what exists - a client paging until it sees an empty page must be able to
			// tell "past the end" from "nothing matched"
			assertEquals(2, past.totalRecordCount());
			assertEquals(0, past.indexes().length);
		}

		@Test
		@DisplayName("orders by handle whichever ordering is asked for")
		void shouldOrderByHandleUnderEitherOrdering() {
			// every catalog index reports an absent entity count, so a size ordering has nothing to discriminate on
			// and must fall back to a total order rather than to whatever order the indexes were collected in
			final List<CatalogIndex> reversed = List.of(empty(Scope.ARCHIVED), empty(Scope.LIVE));
			for (final IndexBrowseOrdering ordering : IndexBrowseOrdering.values()) {
				final IndexBrowseResult result = browse(
					reversed,
					new IndexBrowseCriteria(1, 10, ordering, Set.of(), Set.of(), Set.of())
				);
				assertEquals(
					List.of(0, 1),
					Arrays.stream(result.indexes()).map(BrowsedIndex::indexPrimaryKey).toList(),
					"ordering " + ordering + " must still yield a total order"
				);
			}
		}

		@Test
		@DisplayName("carries the traffic of the index each row describes, and nobody else's")
		void shouldReportTheTrafficOfEachDescribedIndex() {
			// the two scopes are separate indexes with separate holders, and a row is the only place the two can be
			// crossed - the counters they report are the only thing distinguishing these two otherwise identical rows
			final CatalogIndex busy = empty(Scope.LIVE);
			final CatalogIndex idle = empty(Scope.ARCHIVED);
			busy.getActivity().recordQuery(FIRST_MILLIS);
			busy.getActivity().recordUpdate(SECOND_MILLIS);
			busy.getActivity().recordUpdate(THIRD_MILLIS);

			final IndexBrowseResult result = browse(List.of(busy, idle), criteria(1, 10));

			final BrowsedIndex busyRow = rowOfScope(result, Scope.LIVE);
			assertEquals(1L, busyRow.queryCount());
			assertEquals(2L, busyRow.updateCount(), "Two recordings, so a row reading a fresh holder reports zero");
			assertEquals(toTimestamp(FIRST_MILLIS), busyRow.lastQueriedAt());
			assertEquals(toTimestamp(THIRD_MILLIS), busyRow.lastUpdatedAt(), "The stamp is the last one recorded");

			final BrowsedIndex idleRow = rowOfScope(result, Scope.ARCHIVED);
			assertEquals(0L, idleRow.queryCount(), "The archived scope's row reported the live scope's traffic");
			assertEquals(0L, idleRow.updateCount(), "The archived scope's row reported the live scope's traffic");
			assertNull(idleRow.lastQueriedAt());
			assertNull(idleRow.lastUpdatedAt());
		}

	}

	@Nested
	@DisplayName("detail")
	class Detail {

		@Test
		@DisplayName("describes an empty catalog index without inventing readings for it")
		void shouldDescribeAnEmptyCatalogIndex() {
			final IndexDetail detail = CatalogIndexProjection.describe(empty(Scope.LIVE));

			assertNull(detail.entityType());
			assertEquals(0, detail.indexPrimaryKey());
			assertTrue(detail.heapSizeInBytes() > 0, "even an empty index occupies its own object graph");
			assertNull(detail.cardinality().indexType());
			assertEquals(Scope.LIVE, detail.cardinality().scope());
			assertNull(detail.cardinality().discriminator());
			assertNull(detail.cardinality().entityCount());
			assertTrue(detail.cardinality().entityCountIfKnown().isEmpty());
			assertNull(detail.cardinality().referencedEntityCount());
			assertEquals(0, detail.cardinality().attributes().length);
			// nothing has been recorded on this index's holder, so both counters read zero and both stamps state
			// absence rather than the epoch, which a client would render as a date in 1970
			assertEquals(0L, detail.queryCount());
			assertEquals(0L, detail.updateCount());
			assertNull(detail.lastQueriedAt());
			assertNull(detail.lastUpdatedAt());
			assertTrue(detail.lastQueriedAtIfKnown().isEmpty());
			assertTrue(detail.lastUpdatedAtIfKnown().isEmpty());
		}

		@Test
		@DisplayName("reports the traffic recorded on the index it describes")
		void shouldReportTheTrafficOfTheDescribedIndex() {
			final CatalogIndex index = seeded(Scope.ARCHIVED);
			index.getActivity().recordQuery(FIRST_MILLIS);
			index.getActivity().recordQuery(SECOND_MILLIS);
			index.getActivity().recordUpdate(THIRD_MILLIS);

			final IndexDetail detail = CatalogIndexProjection.describe(index);

			assertEquals(2L, detail.queryCount());
			assertEquals(1L, detail.updateCount(), "A query must not be counted as maintenance");
			assertEquals(toTimestamp(SECOND_MILLIS), detail.lastQueriedAt(), "The stamp is the last one recorded");
			assertEquals(toTimestamp(THIRD_MILLIS), detail.lastUpdatedAt());
		}

		@Test
		@DisplayName("reports one attribute reading per global unique index, locale included")
		void shouldReportOneReadingPerGlobalUniqueIndex() {
			final IndexDetail detail = CatalogIndexProjection.describe(seeded(Scope.ARCHIVED));

			assertEquals(1, detail.indexPrimaryKey());
			assertNull(detail.entityType());
			final AttributeCardinality[] attributes = detail.cardinality().attributes();
			assertEquals(2, attributes.length);

			final AttributeCardinality url = readingOf(attributes, "url");
			// a globally unique attribute is declared on the catalog schema and carried by the entity itself, so it is
			// never a reference attribute
			assertNull(url.referenceName());
			assertEquals(Locale.ENGLISH, url.locale());
			assertEquals(AttributeIndexType.UNIQUE, url.indexType());
			assertEquals(3, url.distinctValueCount());
			assertEquals(3, url.recordsCovered());

			final AttributeCardinality code = readingOf(attributes, "code");
			assertNull(code.locale());
			assertEquals(2, code.distinctValueCount());
			assertEquals(2, code.recordsCovered());
		}

		/**
		 * Picks the reading of one attribute out of a detail response.
		 *
		 * @param attributes the readings to search
		 * @param name       name of the attribute to find
		 * @return its reading
		 */
		@Nonnull
		private AttributeCardinality readingOf(
			@Nonnull AttributeCardinality[] attributes,
			@Nonnull String name
		) {
			return Arrays.stream(attributes)
				.filter(it -> name.equals(it.attributeName()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no reading for attribute `" + name + "`"));
		}

	}

	/**
	 * Runs the projection under test.
	 *
	 * @param indexes  the catalog indexes to browse
	 * @param criteria what to select, in what order, and which page
	 * @return the resulting page
	 */
	@Nonnull
	private static IndexBrowseResult browse(
		@Nonnull List<CatalogIndex> indexes,
		@Nonnull IndexBrowseCriteria criteria
	) {
		return CatalogIndexProjection.browse(indexes, criteria, CATALOG_VERSION);
	}

	/**
	 * Picks the row describing the catalog index of one scope out of a page.
	 *
	 * @param result the page to read
	 * @param scope  scope of the index whose row is wanted
	 * @return that index's row
	 */
	@Nonnull
	private static BrowsedIndex rowOfScope(@Nonnull IndexBrowseResult result, @Nonnull Scope scope) {
		return Arrays.stream(result.indexes())
			.filter(it -> scope == it.scope())
			.findFirst()
			.orElseThrow(() -> new AssertionError("no row describes the catalog index of scope " + scope));
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
	 * Builds unfiltered criteria for one page.
	 *
	 * @param pageNumber which page to ask for, 1-indexed
	 * @param pageSize   how many rows the page holds
	 * @return the criteria
	 */
	@Nonnull
	private static IndexBrowseCriteria criteria(int pageNumber, int pageSize) {
		return new IndexBrowseCriteria(
			pageNumber, pageSize, IndexBrowseOrdering.MAP_ORDER, Set.of(), Set.of(), Set.of()
		);
	}

	/**
	 * Builds a catalog index holding nothing, as a scope nothing globally unique has been written into does.
	 *
	 * @param scope scope of the index
	 * @return the index
	 */
	@Nonnull
	private static CatalogIndex empty(@Nonnull Scope scope) {
		return new CatalogIndex(scope);
	}

	/**
	 * Builds a catalog index holding two global unique indexes - one localized, one not - so that both shapes of
	 * attribute key are described.
	 *
	 * @param scope scope of the index
	 * @return the seeded index
	 */
	@Nonnull
	private static CatalogIndex seeded(@Nonnull Scope scope) {
		final Map<AttributeKey, GlobalUniqueIndex> uniqueIndexes = new HashMap<>();
		uniqueIndexes.put(
			new AttributeKey("url", Locale.ENGLISH),
			globalUniqueIndex(scope, new AttributeKey("url", Locale.ENGLISH), Locale.ENGLISH, 3)
		);
		uniqueIndexes.put(
			new AttributeKey("code"),
			globalUniqueIndex(scope, new AttributeKey("code"), null, 2)
		);
		return new CatalogIndex(1, new CatalogIndexKey(scope), uniqueIndexes, new IndexActivity());
	}

	/**
	 * Builds one global unique index holding `values` distinct values, each belonging to one record.
	 *
	 * @param scope        scope of the owning catalog index
	 * @param attributeKey key the index is filed under
	 * @param locale       locale the values are registered under, or null for a non-localized attribute
	 * @param values       how many unique values to register
	 * @return the seeded index
	 */
	@Nonnull
	private static GlobalUniqueIndex globalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nullable Locale locale,
		int values
	) {
		final GlobalUniqueIndex index = new GlobalUniqueIndex(scope, attributeKey, String.class);
		for (int value = 0; value < values; value++) {
			index.registerUniqueKey(
				attributeKey.attributeName() + "-" + value, ENTITY_TYPE, locale, value + 1, RESOLVER
			);
		}
		return index;
	}

}
