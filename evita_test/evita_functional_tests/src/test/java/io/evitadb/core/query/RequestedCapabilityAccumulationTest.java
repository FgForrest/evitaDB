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

package io.evitadb.core.query;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ReferenceSummary;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityGroupFetch;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.entityProperty;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.filterGroupBy;
import static io.evitadb.api.query.QueryConstraints.fromNode;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.having;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfSelf;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinSelf;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.node;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.orderGroupBy;
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.referenceSummary;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReference;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.segment;
import static io.evitadb.api.query.QueryConstraints.segments;
import static io.evitadb.api.query.QueryConstraints.stopAt;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the query side of the schema-capability counters: what a **logical query** asks the schema for, collected
 * while it is planned.
 *
 * The thing being pinned is a deduplication, and it is easy to get wrong in a way nothing else notices. The planner
 * translates the whole filter **once per candidate index set**, so a capability recorded where the translation happens
 * would come out as *"how many alternatives the planner considered"* rather than *"how many queries asked for this"* -
 * a number that moves when the cost model or the fixture's data distribution changes, and that nobody can act on.
 * Every case below therefore plans a query with **more than one candidate index set** and asserts the capability
 * landed exactly once; the multi-candidate premise is itself asserted, so a fixture that quietly stopped producing
 * alternatives cannot make these tests pass vacuously.
 *
 * The complementary property - *requested* is not *chosen* - is asserted against the per-index counters of
 * {@link io.evitadb.index.IndexActivity} in `RequestedIsNotChosen`: a capability consulted on an index that then lost
 * the cost comparison still counts.
 *
 * **What one query asked for is read as the counts it moved**, because {@link QueryPlanBuilder#build()} drains the
 * accumulator into the holders and leaves the context holding nothing. Every case therefore snapshots the collection's
 * registry, runs one query, and asserts on the *difference* - which is what makes a case independent of the fixture's
 * own writes and of any query a sibling case ran before it.
 *
 * `Flush` covers the other half: that the drain happens exactly once per logical query even when the planner is made
 * to build the same plan several times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see QueryPlanningContext#registerRequestedCapability(SchemaCapabilityUsage)
 * @see SchemaCapabilityUsage
 */
@DisplayName("Requested schema capabilities are accumulated once per logical query")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(ATTRIBUTE)
class RequestedCapabilityAccumulationTest implements EvitaTestSupport {
	private static final String CATALOG = "requestedCapabilityAccumulationTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	/** A type whose only entity is archived and references a category, which has no archived entity. */
	private static final String ENTITY_STOCK = "stock";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_EAN = "ean";
	private static final String ATTRIBUTE_ORDER_IN_CATEGORY = "orderInCategory";
	/** The attribute of {@link #ENTITY_CATEGORY}, filterable and sortable in every scope. */
	private static final String ATTRIBUTE_CATEGORY_NAME = "categoryName";
	/** The only stock, archived. */
	private static final int ARCHIVED_STOCK = 1;
	/** The type the categories reference by {@link #REFERENCE_TAGS}. */
	private static final String ENTITY_TAG = "tag";
	/** The reference of {@link #ENTITY_CATEGORY} to the tags; category 1 references tag 1. */
	private static final String REFERENCE_TAGS = "tags";
	/**
	 * A hierarchical type no entity of which exists; it references the categories by {@link #REFERENCE_CATEGORIES}, and
	 * the products reference it by {@link #REFERENCE_BRAND}, which no product holds a row of.
	 */
	private static final String ENTITY_BRAND = "brand";
	/**
	 * The reference of {@link #ENTITY_PRODUCT} to the brands, indexed and faceted in the live scope, its groups being
	 * the tags.
	 */
	private static final String REFERENCE_BRAND = "brand";
	/**
	 * The second reference of {@link #ENTITY_PRODUCT} to the categories, which no product holds a row of - see
	 * {@link #addMainCategoryOfFirstProduct()} for the case adding one.
	 */
	private static final String REFERENCE_MAIN_CATEGORY = "mainCategory";
	/**
	 * The reference of {@link #ENTITY_PRODUCT} to the tags, indexed and bucketed in the live scope but not faceted, its
	 * groups being the categories - a summary of all references describes it only for the histogram it requests. No
	 * product holds a row of it - see {@link #addWeightedTagOfFirstProduct()} for the case adding one.
	 */
	private static final String REFERENCE_WEIGHTED_TAGS = "weightedTags";
	/** The histogram {@link #REFERENCE_WEIGHTED_TAGS} maintains over the weight of the tag. */
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	/**
	 * The reflection of the products of the stocks a case declares on {@link #ENTITY_PRODUCT} - before the reference
	 * {@link #REFERENCE_PRODUCTS} it mirrors exists, so it is not attached while the case queries.
	 */
	private static final String REFERENCE_REFLECTED_STOCKS = "reflectedStocks";
	/** The reference of {@link #ENTITY_STOCK} to the products, declared only to attach the reflection of it. */
	private static final String REFERENCE_PRODUCTS = "products";
	/**
	 * The attribute the categories, the brands and the tags share, filterable in the live scope - the only kind of
	 * attribute the filter of a summary of all references can name, because it is evaluated against every summarized
	 * entity type and every group type.
	 */
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * The types a summary of all references of the products filters by {@link #ATTRIBUTE_LABEL}: the categories and the
	 * brands its options are, and the tags the groups of the brands are.
	 */
	private static final String[] SUMMARIZED_TYPES = {ENTITY_CATEGORY, ENTITY_BRAND, ENTITY_TAG};
	/** The attribute of the {@link #REFERENCE_TAGS} reference, filterable in the live scope. */
	private static final String ATTRIBUTE_WEIGHT = "weight";
	/**
	 * The attribute the categories, the brands and the tags share, filterable and sortable in the live scope - the one
	 * the orderings of the summaries and of the hierarchy statistics name, and the node filters of the brands besides
	 * the label. No entity sets it.
	 */
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String COMPOUND_CODE_WITH_PRIORITY = "codeWithPriority";
	private static final int CATEGORY_COUNT = 4;
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int PRODUCT_COUNT = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;
	/** The category every multi-candidate query names, so the planner builds a plan around its own index. */
	private static final int QUERIED_CATEGORY = 2;

	/** Filtering by `code` - the capability most cases assert on. */
	private static final SchemaCapabilityKey CODE_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE
	);
	/** Ordering by `priority`. */
	private static final SchemaCapabilityKey PRIORITY_SORT = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_PRIORITY, Capability.SORTABLE, Scope.LIVE
	);
	/** Ordering by the compound - the key an attribute of the same name would be indistinguishable from. */
	private static final SchemaCapabilityKey COMPOUND_SORT = SchemaCapabilityKey.sortableCompound(
		null, COMPOUND_CODE_WITH_PRIORITY, Scope.LIVE
	);
	/** Filtering by an attribute the `categories` reference declares. */
	private static final SchemaCapabilityKey ORDER_IN_CATEGORY_FILTER = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_CATEGORIES, ATTRIBUTE_ORDER_IN_CATEGORY, Capability.FILTERABLE, Scope.LIVE
	);
	/** Ordering by an attribute the `categories` reference declares. */
	private static final SchemaCapabilityKey ORDER_IN_CATEGORY_SORT = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_CATEGORIES, ATTRIBUTE_ORDER_IN_CATEGORY, Capability.SORTABLE, Scope.LIVE
	);
	/** The control: a filterable attribute of the same shape as `code` that no query below ever names. */
	private static final SchemaCapabilityKey EAN_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
	);
	/** The `categories` reference's own `indexed()` flag - the reference is the element, not the container. */
	private static final SchemaCapabilityKey CATEGORIES_INDEXED = SchemaCapabilityKey.reference(
		REFERENCE_CATEGORIES, Capability.INDEXED, Scope.LIVE
	);
	/** The same reference's own `faceted()` flag, which the fixture declares for the live scope only. */
	private static final SchemaCapabilityKey CATEGORIES_FACETED_LIVE = SchemaCapabilityKey.reference(
		REFERENCE_CATEGORIES, Capability.FACETED, Scope.LIVE
	);
	/** The archive counterpart of the flag above - a scope the fixture deliberately leaves undeclared. */
	private static final SchemaCapabilityKey CATEGORIES_FACETED_ARCHIVED = SchemaCapabilityKey.reference(
		REFERENCE_CATEGORIES, Capability.FACETED, Scope.ARCHIVED
	);
	/** Filtering by an attribute the `tags` reference of the categories declares. */
	private static final SchemaCapabilityKey WEIGHT_FILTER = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_TAGS, ATTRIBUTE_WEIGHT, Capability.FILTERABLE, Scope.LIVE
	);
	/** The `tags` reference's own `indexed()` flag. */
	private static final SchemaCapabilityKey TAGS_INDEXED = SchemaCapabilityKey.reference(
		REFERENCE_TAGS, Capability.INDEXED, Scope.LIVE
	);

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RequestedCapabilityAccumulationTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Deduplication")
	class Deduplication {

		@Test
		@DisplayName("A filter and an ordering each land once, however many candidate plans were costed")
		void shouldAccumulateEachCapabilityExactlyOncePerLogicalQuery() {
			final Query query = query(
				filterBy(
					and(
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
					)
				),
				orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC))
			);
			assertTrue(
				candidatePlanCountOf(query) > 1,
				"The fixture must make the planner cost more than one candidate index set, otherwise this test " +
					"cannot tell a deduplicated count from a raw one"
			);

			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(query);

			assertEquals(
				2, requested.size(),
				"One logical query must move one capability's count per element it names - more entries means the " +
					"accumulator is counting candidate plans: " + requested
			);
			assertRequested(requested, CODE_FILTER);
			assertRequested(requested, PRIORITY_SORT);
		}

		@Test
		@DisplayName("Naming the same attribute in several constraints is still one request")
		void shouldAccumulateOneEntryForAnAttributeNamedRepeatedly() {
			final Query query = query(
				filterBy(
					and(
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
					)
				)
			);

			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(query);

			assertEquals(
				1, requested.size(),
				"Repeating a constraint must not repeat the request: " + requested
			);
			assertRequested(requested, CODE_FILTER);
		}

		@Test
		@DisplayName("A query naming no attribute at all accumulates nothing")
		void shouldAccumulateNothingForAQueryThatNamesNoAttribute() {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(filterBy(entityPrimaryKeyInSet(1, 2, 3)))
			);

			assertTrue(requested.isEmpty(), "Nothing was named, so nothing may be requested: " + requested);
		}

	}

	@Nested
	@DisplayName("Which element the request lands on")
	class Attribution {

		@Test
		@DisplayName("A sortable compound is counted as the compound, not as an attribute of the same name")
		void shouldAccumulateTheCompoundUnderItsOwnKind() {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					orderBy(attributeNatural(COMPOUND_CODE_WITH_PRIORITY, OrderDirection.ASC))
				)
			);

			assertRequested(requested, COMPOUND_SORT);
			assertNotRequested(
				requested,
				SchemaCapabilityKey.entityAttribute(COMPOUND_CODE_WITH_PRIORITY, Capability.SORTABLE, Scope.LIVE),
				"The compound was recorded as if it were an attribute, which pools it with an attribute that may " +
					"legitimately carry the same name"
			);
		}

		@Test
		@DisplayName("A reference attribute is counted on its reference, not on the entity")
		void shouldAccumulateAReferenceAttributeUnderItsReference() {
			final Map<SchemaCapabilityKey, Long> filtering = capabilitiesRequestedBy(
				query(
					filterBy(
						referenceHaving(REFERENCE_CATEGORIES, attributeEquals(ATTRIBUTE_ORDER_IN_CATEGORY, 1L))
					)
				)
			);
			assertRequested(filtering, ORDER_IN_CATEGORY_FILTER);
			// reaching an attribute *of* a reference is itself a dependency on the reference's `indexed()`: the
			// reduced index the attribute's filter index lives in exists only because the reference is indexed, so a
			// query that filters on the attribute would break if the flag were dropped
			assertRequested(filtering, CATEGORIES_INDEXED);
			assertNotRequested(
				filtering,
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER_IN_CATEGORY, Capability.FILTERABLE, Scope.LIVE),
				"The reference's attribute was recorded as an attribute of the entity, which pools two elements the " +
					"schema keeps apart"
			);

			final Map<SchemaCapabilityKey, Long> ordering = capabilitiesRequestedBy(
				query(
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					orderBy(
						referenceProperty(
							REFERENCE_CATEGORIES, attributeNatural(ATTRIBUTE_ORDER_IN_CATEGORY, OrderDirection.ASC)
						)
					)
				)
			);
			assertRequested(ordering, ORDER_IN_CATEGORY_SORT);
			// the ordering reaches the reference's `indexed()` twice - once through the sort translator and once
			// through the attribute lookup - and is still one request
			assertRequested(ordering, CATEGORIES_INDEXED);
		}

		@Test
		@DisplayName("An attribute no query names is not requested by one that names its neighbours")
		void shouldNotRequestACapabilityTheQueryNeverNames() {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(
					filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3")),
					orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC))
				)
			);

			// `ean` is filterable, of the same shape as `code`, and written by every product the fixture upserts - so
			// an accumulator that recorded whatever the schema declares, or whatever a write touched, fails here
			assertNotRequested(
				requested, EAN_FILTER,
				"A filterable attribute nothing has asked for was requested anyway - a capability advancing on every " +
					"query, which is how this could plausibly go wrong, would make the whole reading useless"
			);
			// the same query's own capabilities did land, so the absence above is a real negative rather than an
			// accumulator that recorded nothing whatsoever
			assertRequested(requested, CODE_FILTER);
			assertRequested(requested, PRIORITY_SORT);
		}

	}

	@Nested
	@DisplayName("The flags a reference declares on itself")
	@Tag(REFERENCE)
	@Tag(FACET)
	class ReferenceOwnFlags {

		@Test
		@DisplayName("A facet filter naming two scopes records only the scope that declares the faceting")
		void shouldRecordFacetedWhenAFacetFilterNamesSeveralScopes() {
			// `facetHaving` is legal as long as *one* of the named scopes declares `faceted()`, and the fixture
			// declares it for the live scope only - which makes this the one query shape able to tell a per-scope
			// recording apart from one that files the whole requested set
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(
					filterBy(
						and(
							scope(Scope.LIVE, Scope.ARCHIVED),
							facetHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
						)
					)
				)
			);

			assertRequested(requested, CATEGORIES_FACETED_LIVE);
			assertNotRequested(
				requested, CATEGORIES_FACETED_ARCHIVED,
				"A scope that declares no faceting was counted as depended upon - such a row can never be matched by " +
					"a write, so it reads as a capability nothing maintains for a flag that is simply not there"
			);
		}

	}

	@Nested
	@DisplayName("Requested is not chosen")
	class RequestedIsNotChosen {

		@Test
		@DisplayName("A capability consulted on an index that lost the cost comparison still counts")
		void shouldAccumulateEvenWhenThePlannerPicksADifferentIndex() {
			// the reference filter makes the planner build a full candidate plan around the global index and another
			// around the category's own reduced one, translating `code` against both. On this fixture the reduced one
			// wins, so the global index - where `code`'s filter index actually lives for the whole collection - is
			// consulted and discarded. The two readings are meant to disagree here, and that disagreement is the
			// point: `requested` says the query would break without `filterable()` on `code`, whatever plan won
			final Query query = query(
				filterBy(
					and(
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
					)
				)
			);
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(query);

			assertEquals(
				1L, reducedIndexOfCategory(QUERIED_CATEGORY).getActivity().getQueryCount(),
				"The test needs a candidate to have won on the reduced index; a cost-model change that reverses this " +
					"surfaces here first, and what has to be re-pointed is which index is expected to lose"
			);
			assertEquals(
				0L, globalIndex().getActivity().getQueryCount(),
				"The global index must have lost the cost comparison, otherwise there is no discarded candidate left " +
					"for this case to be about"
			);
			assertRequested(requested, CODE_FILTER);
		}

	}

	@Nested
	@DisplayName("Flush onto the holders")
	class Flush {

		@Test
		@DisplayName("An executed query moves the capability's request count by one and its update count by none")
		void shouldCountOneRequestPerExecutedQuery() {
			// end to end, through a real session: what the operator will eventually read is the collection's registry,
			// not a context nobody outside the planner can reach
			final SchemaCapabilityUsage holder = holderOf(CODE_FILTER);
			final long requestedBefore = holder.getRequestedCount();
			final long updatedBefore = holder.getUpdatedCount();
			assertTrue(
				updatedBefore > 0L,
				"The fixture's upserts must already have counted maintenance on `code`, otherwise the update side " +
					"standing still below proves nothing about the two counters being independent"
			);

			executeQuery(query(filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3"))));

			assertEquals(
				requestedBefore + 1, holder.getRequestedCount(),
				"One query filtering by `code` must land exactly one request on the registry the collection holds"
			);
			assertEquals(
				updatedBefore, holder.getUpdatedCount(),
				"Reading must not have counted as maintenance - the two sides answer different questions and a query " +
					"that bumped both would make the comparison between them meaningless"
			);
			assertTrue(
				holder.getLastRequestedAtMillis() >= holder.getObservedSinceMillis(),
				"A requested capability must carry the stamp of it, not the `never` sentinel"
			);
		}

		@Test
		@DisplayName("Building the winning plan empties the accumulator, so any further build counts nothing")
		void shouldLeaveTheAccumulatorEmptyAfterTheWinningPlanWasBuilt() {
			final SchemaCapabilityUsage holder = holderOf(CODE_FILTER);
			final long before = holder.getRequestedCount();
			final QueryPlanningContext context = planningContextFor(
				query(filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3")))
			);

			QueryPlanner.planQuery(context);

			assertEquals(before + 1, holder.getRequestedCount(), "The built plan must have flushed what it collected");
			assertTrue(
				context.drainRequestedCapabilities().isEmpty(),
				"The build left the accumulator populated - anything building this plan again would count the same " +
					"logical query a second time"
			);
		}

		@Test
		@DisplayName("A query verified against every alternative plan is still one request")
		void shouldCountOnceUnderTheAlternativeIndexVerification() {
			assertCountedOnceWhileTheWinningPlanIsBuiltRepeatedly(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS);

			// the same query leaves the global index at zero without this mode - see `RequestedIsNotChosen` - so a
			// reading above zero is proof the losing candidate really was built and executed here
			assertTrue(
				globalIndex().getActivity().getQueryCount() > 0L,
				"The losing candidate was never built, so this mode verified nothing and the case is vacuous"
			);
		}

		@Test
		@DisplayName("A query verified against its cacheable variants is still one request")
		void shouldCountOnceUnderTheCachingTreeVerification() {
			// what this pins is the preferred plan being built a second time to be returned. The other half of the
			// mode - equipping each cacheable variant with a sorter of its own, which re-plans the ordering *after*
			// that build already drained - is not reached on this fixture, whose filter formulas hold no
			// `CacheableFormula` for `CacheableVariantsGeneratingVisitor` to vary; that residual is documented on
			// `QueryPlanningContext#drainRequestedCapabilities` rather than asserted here
			assertCountedOnceWhileTheWinningPlanIsBuiltRepeatedly(DebugMode.VERIFY_POSSIBLE_CACHING_TREES);
		}

		@Test
		@DisplayName("A query whose reference constraint matches nothing counts what its twin matching data counts")
		void shouldCountQueryWhoseReferenceConstraintMatchesNothing() {
			// index selection comes back empty for a category no product references, and the planner answers with the
			// empty plan after checking the constraints over empty indexes - the query still named `code` and
			// `priority`, and dropping either flag would break it, so it counts exactly like its twin that matches data
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByExecuting(
				productsOfCategoryOrderedByPriority(CATEGORY_COUNT + 1)
			);
			final Map<SchemaCapabilityKey, Long> requestedByTwin = capabilitiesRequestedByExecuting(
				productsOfCategoryOrderedByPriority(QUERIED_CATEGORY)
			);

			assertEquals(
				Map.of(CODE_FILTER, 1L, PRIORITY_SORT, 1L), requestedByTwin,
				"The twin matching data must count each capability it named once"
			);
			assertEquals(
				requestedByTwin, requested,
				"The query matching nothing must count exactly what its twin matching data counts"
			);
		}

		@Test
		@DisplayName("A query over a scope without data counts what its twin over a scope with data counts")
		void shouldCountQueryOverScopeWithoutData() {
			// the categories hold no archived entity, so index selection comes back empty for the archived scope and
			// the planner answers with the empty plan after checking the filter and the ordering over empty indexes
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, categoriesByNameInScope(Scope.ARCHIVED)
			);
			final Map<SchemaCapabilityKey, Long> requestedByTwin = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, categoriesByNameInScope(Scope.LIVE)
			);

			assertEquals(
				Map.of(
					categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L,
					categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L
				),
				requestedByTwin,
				"The twin over the scope with data must count each capability it named once"
			);
			assertEquals(
				Map.of(
					categoryNameKey(Capability.FILTERABLE, Scope.ARCHIVED), 1L,
					categoryNameKey(Capability.SORTABLE, Scope.ARCHIVED), 1L
				),
				requested,
				"The query over the scope without data must count each capability it named once, like its twin"
			);
		}

		@Test
		@DisplayName("A nested query over a scope without data counts what its twin over a scope with data counts")
		void shouldCountNestedQueryOverScopeWithoutData() {
			// the references of the archived stock are ordered by a nested query over the archived categories, of
			// which there is none - that nested query is answered by the empty plan, while the same ordering of the
			// references of a live product is planned over the live categories
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, entityWithCategoriesOrderedByName(ENTITY_STOCK, Scope.ARCHIVED)
			);
			final Map<SchemaCapabilityKey, Long> requestedByTwin = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, entityWithCategoriesOrderedByName(ENTITY_PRODUCT, Scope.LIVE)
			);

			assertEquals(
				Map.of(categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L), requestedByTwin,
				"The nested query over the scope with data must count the capability it named once"
			);
			assertEquals(
				Map.of(categoryNameKey(Capability.SORTABLE, Scope.ARCHIVED), 1L), requested,
				"The nested query over the scope without data must count the capability it named once, like its twin"
			);
		}

		@Test
		@DisplayName("A filter of the fetched references counts once, whether or not there is a reference to filter")
		void shouldCountFilterOfFetchedReferencesOnce() {
			// the filter is checked while the query is planned, before the winning plan drains the accumulator, and
			// translated again by the fetch only when a fetched product references something - which must not count
			// the same request a second time
			final Map<SchemaCapabilityKey, Long> requestedWithReference = capabilitiesRequestedByFetching(
				ENTITY_PRODUCT, productWithCategoriesFilteredByOrder(1)
			);
			final Map<SchemaCapabilityKey, Long> requestedWithoutProduct = capabilitiesRequestedByFetching(
				ENTITY_PRODUCT, productWithCategoriesFilteredByOrder(PRODUCT_COUNT + 1)
			);

			final Map<SchemaCapabilityKey, Long> expected = Map.of(
				ORDER_IN_CATEGORY_FILTER, 1L, CATEGORIES_INDEXED, 1L
			);
			assertEquals(
				expected, requestedWithReference,
				"The query fetching a filtered reference must count the capabilities its filter named once"
			);
			assertEquals(
				expected, requestedWithoutProduct,
				"The query fetching no reference must count what its twin fetching one counts"
			);
		}

		@Test
		@DisplayName("An entity filter of the fetched references counts once on the referenced collection")
		void shouldCountEntityFilterOfFetchedReferencesOnceOnReferencedCollection() {
			// the fetch plans the filter of the referenced categories as a nested query of their collection, which
			// counts what it named there; the check made while the query is planned plans no nested query, so it must
			// not add a second count
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY,
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(entityPrimaryKeyInSet(1)),
					require(
						entityFetch(
							referenceContent(
								REFERENCE_CATEGORIES,
								filterBy(entityHaving(attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1")))
							)
						)
					)
				)
			);

			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L), requested,
				"The filter of the referenced categories must be counted once on their collection"
			);
		}

		@Test
		@DisplayName("A filter of the references of the fetched referenced entities counts once on their collection")
		void shouldCountFilterOfReferencesOfFetchedReferencedEntitiesOnce() {
			// the categories fetched with a product own the references to the tags, so the filter of those references
			// asks the schema of the categories - it is checked while the query is planned, in a context of the
			// category collection, and must be counted once whether or not there is a category to fetch
			final Map<SchemaCapabilityKey, Long> requestedWithReference = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, productWithCategoriesWithTagsFilteredByWeight(1)
			);
			final Map<SchemaCapabilityKey, Long> requestedWithoutProduct = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, productWithCategoriesWithTagsFilteredByWeight(PRODUCT_COUNT + 1)
			);

			final Map<SchemaCapabilityKey, Long> expected = Map.of(WEIGHT_FILTER, 1L, TAGS_INDEXED, 1L);
			assertEquals(
				expected, requestedWithReference,
				"The query fetching a category with filtered tags must count the capabilities the filter named once"
			);
			assertEquals(
				expected, requestedWithoutProduct,
				"The query fetching no category must count what its twin fetching one counts"
			);
		}

		@Test
		@DisplayName("An entity filter of a reference counts the target's flag in a scope where the target holds nothing")
		void shouldCountEntityFilterOfReferenceInScopeWithoutTargetData() {
			// the products of both scopes are filtered by the name of their category: the live categories are queried
			// by a nested query, which counts the live flag it named; the categories hold no archived entity, so the
			// filter is only checked against the archived scope - it named the archived flag all the same
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, productsOfCategoryNamed(Scope.LIVE, Scope.ARCHIVED)
			);

			assertEquals(
				Map.of(
					categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L,
					categoryNameKey(Capability.FILTERABLE, Scope.ARCHIVED), 1L
				),
				requested,
				"The filter of the categories must be counted once in each scope, with and without categories there"
			);
		}

		@Test
		@DisplayName("An entity filter of a reference of a query matching nothing counts the target's flag")
		void shouldCountEntityFilterOfReferenceOfQueryMatchingNothing() {
			// the products hold no archived entity, so the query is answered by the empty plan after its filter was
			// checked, and the categories hold no archived entity either, so their filter is only checked - the query
			// named the archived flag of the category name all the same
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, productsOfCategoryNamed(Scope.ARCHIVED)
			);

			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.ARCHIVED), 1L), requested,
				"The filter of the categories must be counted once for the query matching nothing"
			);
		}

		@Test
		@DisplayName("An entity filter of the fetched references counts once in a scope where the target holds nothing")
		void shouldCountEntityFilterOfFetchedReferencesInScopeWithoutTargetDataOnce() {
			// the products of both scopes fetch their categories filtered by name: the categories hold no archived
			// entity, so no nested query is planned for the archived scope, neither while the query is planned nor
			// when a product is fetched - the check made while planning is the only place the archived flag is
			// requested, and it must be counted once whether or not there is a product to fetch; the live flag is
			// counted once by the nested query the fetch plans over the live categories
			final Map<SchemaCapabilityKey, Long> requestedWithProduct = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, productOfBothScopesWithCategoriesFilteredByName(1)
			);
			final Map<SchemaCapabilityKey, Long> requestedWithoutProduct = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY, productOfBothScopesWithCategoriesFilteredByName(PRODUCT_COUNT + 1)
			);

			assertEquals(
				Map.of(
					categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L,
					categoryNameKey(Capability.FILTERABLE, Scope.ARCHIVED), 1L
				),
				requestedWithProduct,
				"The query fetching a product must count the flags the filter of its categories named once"
			);
			assertEquals(
				1L, requestedWithoutProduct.get(categoryNameKey(Capability.FILTERABLE, Scope.ARCHIVED)),
				"The query fetching no product must count the archived flag like its twin fetching one"
			);
		}

		@Test
		@DisplayName("An entity filter nested in a checked entity filter counts the inner target's flag where it has data")
		void shouldCountEntityFilterNestedInCheckedEntityFilter() {
			// the brands hold no entity, so their filter is only checked and no nested query of the brands is planned -
			// nor of the categories the brand filter nests, although the categories hold live data: the check is the
			// only place the live flag of the category name is requested, and it must be counted once
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY,
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(
						referenceHaving(
							REFERENCE_BRAND,
							entityHaving(
								referenceHaving(
									REFERENCE_CATEGORIES,
									entityHaving(attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1"))
								)
							)
						)
					)
				)
			);

			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L), requested,
				"The filter of the categories nested in the checked filter of the brands must be counted once"
			);
		}

		@Test
		@DisplayName("The filter of the options of a reference summary counts the target's flag once")
		void shouldCountFilterOfReferenceSummaryOptionsOnce() {
			// the filter of the summarized categories is planned in a context of the category collection while the
			// query is planned, and that context builds no plan of its own - the query counts what it requested
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY,
				Query.query(
					collection(ENTITY_PRODUCT),
					require(
						referenceSummaryOfReference(
							REFERENCE_CATEGORIES,
							FacetStatisticsDepth.COUNTS,
							filterBy(attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1"))
						)
					)
				)
			);

			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L), requested,
				"The filter of the summarized categories must be counted once on their collection"
			);
		}

		@Test
		@DisplayName("A hierarchy filter of the options of a reference summary counts the target's hierarchy flag once")
		void shouldCountHierarchyFilterOfReferenceSummaryOptionsOnce() {
			// `hierarchyWithinSelf` resolves against the tree of the summarized brands and requests their hierarchy
			// flag in the context it is planned in - the query must count it once
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_BRAND,
				Query.query(
					collection(ENTITY_PRODUCT),
					require(
						referenceSummaryOfReference(
							REFERENCE_BRAND,
							FacetStatisticsDepth.COUNTS,
							filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1)))
						)
					)
				)
			);

			assertEquals(
				Map.of(SchemaCapabilityKey.entity(ENTITY_BRAND, Capability.HIERARCHICAL, Scope.LIVE), 1L), requested,
				"The hierarchy filter of the summarized brands must be counted once on their collection"
			);
		}

		@Test
		@DisplayName("The filters of a summary of all references count each target's flag once, with and without data")
		void shouldCountFiltersOfSummaryOfAllReferencesOnceWithAndWithoutData() {
			// the filter of the options is evaluated against the categories, which hold data, and against the brands,
			// which hold none at first; the filter of the groups against the tags, the groups of the brands - each of
			// them requests the label of its type, whether there is an option or a group to filter or not
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					referenceSummary(
						FacetStatisticsDepth.COUNTS,
						filterBy(attributeEquals(ATTRIBUTE_LABEL, "label-1")),
						filterGroupBy(attributeEquals(ATTRIBUTE_LABEL, "label-1"))
					)
				)
			);

			assertEquals(
				labelRequestedOnEachSummarizedType(), capabilitiesRequestedOn(query, SUMMARIZED_TYPES),
				"The filters of the summary must count the label of each summarized type once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				labelRequestedOnEachSummarizedType(), capabilitiesRequestedOn(query, SUMMARIZED_TYPES),
				"The filters of the summary must count the label of each summarized type once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The filters of a summary of all references of a query matching nothing count each target's flag")
		void shouldCountFiltersOfSummaryOfAllReferencesOfQueryMatchingNothing() {
			// no product references the category, so the query is answered by the empty plan - there is no option to
			// summarize, but the filters requested the label of every summarized type all the same
			final Map<String, Map<SchemaCapabilityKey, Long>> requested = capabilitiesRequestedOn(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(CATEGORY_COUNT + 1))),
					require(
						referenceSummary(
							FacetStatisticsDepth.COUNTS,
							filterBy(attributeEquals(ATTRIBUTE_LABEL, "label-1")),
							filterGroupBy(attributeEquals(ATTRIBUTE_LABEL, "label-1"))
						)
					)
				),
				SUMMARIZED_TYPES
			);

			assertEquals(
				labelRequestedOnEachSummarizedType(), requested,
				"The filters of the summary of the query matching nothing must count the label of each type once"
			);
		}

		@Test
		@DisplayName("The filters of a facet summary of all references count each target's flag once")
		void shouldCountFiltersOfFacetSummaryOfAllReferencesOnce() {
			// the deprecated form shares the translation of the summary of all references
			@SuppressWarnings("deprecation") final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					facetSummary(
						FacetStatisticsDepth.COUNTS,
						filterBy(attributeEquals(ATTRIBUTE_LABEL, "label-1")),
						filterGroupBy(attributeEquals(ATTRIBUTE_LABEL, "label-1"))
					)
				)
			);

			assertEquals(
				labelRequestedOnEachSummarizedType(), capabilitiesRequestedOn(query, SUMMARIZED_TYPES),
				"The filters of the facet summary must count the label of each summarized type once"
			);
		}

		@Test
		@DisplayName("The filter of summarized options an entity filter also names counts once, with and without data")
		void shouldCountFilterOfSummarizedOptionsAlsoNamedByEntityFilterOnce() {
			// the categories hold data, so their entity filter is evaluated by a nested query, which builds a plan of
			// its own; the summary of the same reference names the same flag - the query asked for it once
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY,
				productsFilteredAndSummarizedByTarget(REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_NAME, "category-1")
			);
			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L), requested,
				"The name of the category named by both the entity filter and the summary must be counted once"
			);

			// the brands hold no entity at first, so their entity filter is only checked, and then one, so it is
			// evaluated by a nested query - the count must not tell the two apart
			final Query brandQuery = productsFilteredAndSummarizedByTarget(REFERENCE_BRAND, ATTRIBUTE_LABEL, "label-1");
			final Map<SchemaCapabilityKey, Long> expectedOnBrand = Map.of(labelKey(), 1L);
			assertEquals(
				expectedOnBrand, capabilitiesRequestedByFetching(ENTITY_BRAND, brandQuery),
				"The label of the brand named by both the entity filter and the summary must be counted once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expectedOnBrand, capabilitiesRequestedByFetching(ENTITY_BRAND, brandQuery),
				"The label of the brand named by both the entity filter and the summary must be counted once, now " +
					"that a brand exists"
			);
		}

		@Test
		@DisplayName("An attribute two entity filters name counts once, with and without data")
		void shouldCountAttributeNamedByTwoEntityFiltersOnce() {
			// each entity filter of the categories is evaluated by a nested query of its own, which builds a plan of
			// its own - the query asked for the name of the category once
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY,
				productsFilteredTwiceByTarget(REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_NAME, "category-1", "category-2")
			);
			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L), requested,
				"The name of the category named by two entity filters must be counted once"
			);

			// the brands hold no entity at first, so their entity filters are only checked, and then one, so they are
			// evaluated by nested queries - the count must not tell the two apart
			final Query brandQuery = productsFilteredTwiceByTarget(REFERENCE_BRAND, ATTRIBUTE_LABEL, "label-1", "label-2");
			final Map<SchemaCapabilityKey, Long> expectedOnBrand = Map.of(labelKey(), 1L);
			assertEquals(
				expectedOnBrand, capabilitiesRequestedByFetching(ENTITY_BRAND, brandQuery),
				"The label of the brand named by two entity filters must be counted once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expectedOnBrand, capabilitiesRequestedByFetching(ENTITY_BRAND, brandQuery),
				"The label of the brand named by two entity filters must be counted once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("An attribute an entity filter and the filter of the fetched references name counts once")
		void shouldCountAttributeNamedByEntityFilterAndFilterOfFetchedReferencesOnce() {
			// the entity filter of the categories is evaluated by a nested query planned with the query, the filter of
			// the fetched categories by another one the fetch plans - the query asked for the name of the category once
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByFetching(
				ENTITY_CATEGORY,
				productsFilteredAndFetchedByTarget(REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_NAME, "category-1")
			);
			assertEquals(
				Map.of(categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L), requested,
				"The name of the category named by the entity filter and the filter of the fetched references must " +
					"be counted once"
			);

			// the brands hold no entity at first, so both filters are only checked, and then one, so they are evaluated
			// by nested queries - the count must not tell the two apart
			final Query brandQuery = productsFilteredAndFetchedByTarget(REFERENCE_BRAND, ATTRIBUTE_LABEL, "label-1");
			final Map<SchemaCapabilityKey, Long> expectedOnBrand = Map.of(labelKey(), 1L);
			assertEquals(
				expectedOnBrand, capabilitiesRequestedByFetching(ENTITY_BRAND, brandQuery),
				"The label of the brand named by the entity filter and the filter of the fetched references must be " +
					"counted once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expectedOnBrand, capabilitiesRequestedByFetching(ENTITY_BRAND, brandQuery),
				"The label of the brand named by the entity filter and the filter of the fetched references must be " +
					"counted once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The group filter of a summary of all references counts on a histogram-only reference once")
		void shouldCountGroupFilterOfSummaryOfAllReferencesOnHistogramOnlyReferenceOnce() {
			// the weighted tags are not faceted, so the summary of all references describes them only for the histogram
			// it requests - computed for their groups, the categories, which the group filter narrows whether a product
			// references a weighted tag or not; no other reference is grouped by the categories
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					new ReferenceSummary(
						FacetStatisticsDepth.COUNTS,
						null,
						filterGroupBy(attributeEquals(ATTRIBUTE_LABEL, "label-1")),
						null,
						null,
						histogramStatistics(10, HISTOGRAM_WEIGHT)
					)
				)
			);
			final Map<SchemaCapabilityKey, Long> expected = Map.of(labelKey(), 1L);

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_CATEGORY, query),
				"The group filter must count the label of the categories once while no product references a " +
					"weighted tag"
			);

			addWeightedTagOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_CATEGORY, query),
				"The group filter must count the label of the categories once, now that a product references a " +
					"weighted tag"
			);
		}

		@Test
		@DisplayName("An ordering of the fetched references by an attribute of their target counts without data")
		void shouldCountOrderingOfFetchedReferencesByTargetAttributeWithoutData() {
			// no product is fetched, so no comparator orders a reference and no query over the categories is planned -
			// the ordering is only checked while the query is planned, and the query named the name of the category to
			// order by all the same
			assertEquals(
				Map.of(categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L),
				capabilitiesRequestedByFetching(
					ENTITY_CATEGORY, productWithBothCategoryReferencesOrderedByName(PRODUCT_COUNT + 1)
				),
				"The query fetching no product must count the ordering of the categories once"
			);
		}

		@Test
		@DisplayName("Two fetched references ordered by the same attribute of their target count it once")
		void shouldCountOrderingOfTwoFetchedReferencesByTheSameTargetAttributeOnce() {
			// the comparator of each fetched reference holding a row plans a query over the categories to order them -
			// the query asked for the name of the category to order by once, whichever of its references hold a row
			final Map<SchemaCapabilityKey, Long> expected = Map.of(
				categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L
			);

			assertEquals(
				expected,
				capabilitiesRequestedByFetching(ENTITY_CATEGORY, productWithBothCategoryReferencesOrderedByName(1)),
				"The query fetching a product holding a row of one of the references must count the ordering once"
			);

			addMainCategoryOfFirstProduct();

			assertEquals(
				expected,
				capabilitiesRequestedByFetching(ENTITY_CATEGORY, productWithBothCategoryReferencesOrderedByName(1)),
				"The query fetching a product holding a row of both references must count the ordering once"
			);
		}

		@Test
		@DisplayName("A query refused over a scope without index counts nothing")
		void shouldCountNothingWhenQueryOverScopeWithoutIndexIsRefused() {
			// the catalog holds no index of the archived scope, and `code` is not filterable there - the query fails
			// exactly as it fails where the entities exist, and a refused query asked nothing
			final Map<SchemaCapabilityKey, Long> before = requestedCounts();
			assertThrowsExactly(
				AttributeNotFilterableException.class,
				() -> executeQuery(
					Query.query(
						collection(ENTITY_PRODUCT),
						filterBy(and(scope(Scope.ARCHIVED), attributeEquals(ATTRIBUTE_CODE, "product-3")))
					)
				)
			);
			final Map<SchemaCapabilityKey, Long> requested = requestedCountsSince(before);

			assertTrue(
				requested.isEmpty(),
				"The refused query counted a request: " + requested
			);
		}

		/**
		 * Runs one multi-candidate query under a verification debug mode and asserts the two readings part ways: the
		 * winning index counts a query per plan built, the capability counts once.
		 *
		 * The per-index reading is what keeps this from passing vacuously - it proves the debug mode really did build
		 * the winning plan more than once, which is the hazard being pinned. Without it a mode that silently stopped
		 * verifying anything would look like a successful deduplication.
		 *
		 * @param debugMode the verification mode to enable
		 */
		private void assertCountedOnceWhileTheWinningPlanIsBuiltRepeatedly(@Nonnull DebugMode debugMode) {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByExecuting(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(
						and(
							attributeEquals(ATTRIBUTE_CODE, "product-3"),
							referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
						)
					),
					orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC)),
					require(debug(debugMode))
				)
			);

			assertTrue(
				reducedIndexOfCategory(QUERIED_CATEGORY).getActivity().getQueryCount() > 1L,
				"`" + debugMode + "` did not build the winning plan more than once, so this case proves nothing " +
					"about a double flush"
			);
			assertRequested(requested, CODE_FILTER);
			assertRequested(requested, PRIORITY_SORT);
		}

	}

	@Nested
	@DisplayName("A reflected reference not attached yet")
	class UnattachedReflection {

		@Test
		@DisplayName("A summary of all references filtering groups is answered past a faceted unattached reflection")
		void shouldAnswerGroupFilterOfSummaryOfAllReferencesPastUnattachedFacetedReflection() {
			// the reflection states its own `faceted()`, so the summary of all references covers it - but the group
			// type of a reflection is the one of the reference it mirrors, which does not exist yet, and a reflection
			// asked for it throws; the reflection holds no option, so its groups have nothing to filter
			assertAnsweredWhileReflectionIsNotAttached(
				whichIs -> whichIs.indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE),
				Query.query(
					collection(ENTITY_PRODUCT),
					require(
						new ReferenceSummary(
							FacetStatisticsDepth.COUNTS, null, filterGroupBy(entityPrimaryKeyInSet(1)), null, null
						)
					)
				)
			);
		}

		@Test
		@DisplayName("A summary of all references fetching its groups is answered past a faceted unattached reflection")
		void shouldAnswerGroupFetchOfSummaryOfAllReferencesPastUnattachedFacetedReflection() {
			// the fetch of the groups is checked against the group type of every summarized reference, and the
			// reflection cannot tell whether its group type is managed before it is attached
			assertAnsweredWhileReflectionIsNotAttached(
				whichIs -> whichIs.indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE),
				Query.query(
					collection(ENTITY_PRODUCT),
					require(new ReferenceSummary(FacetStatisticsDepth.COUNTS, entityGroupFetch()))
				)
			);
		}

		@Test
		@DisplayName("A summary of all references fetching its options is answered past an unattached reflection")
		void shouldAnswerFetchOfSummaryOfAllReferencesPastUnattachedReflectionInheritingFaceting() {
			// the reflection inherits its `faceted()`, which it cannot tell before it is attached - the fetch of the
			// options must step over it as the recording of the flag does, rather than ask
			assertAnsweredWhileReflectionIsNotAttached(
				whichIs -> {
				},
				Query.query(
					collection(ENTITY_PRODUCT),
					require(new ReferenceSummary(FacetStatisticsDepth.COUNTS, entityFetch()))
				)
			);
		}

	}

	@Nested
	@DisplayName("An ordering planned in a sorter context of its own")
	class SorterContexts {

		@Test
		@DisplayName("An ordering nested in the ordering of fetched references counts, with and without data")
		void shouldCountOrderingNestedInOrderingOfFetchedReferencesWithAndWithoutData() {
			// the fetched brands are ordered by the name of the first category each brand references - the check of the
			// ordering plans a sorter over the brands, and translating its `referenceProperty` plans another one over
			// the categories inside it, whose context is a child of the brand sorter's context
			final Map<SchemaCapabilityKey, Long> expected = Map.of(
				categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L
			);

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_CATEGORY, productWithBrandOrderedByCategoryName()),
				"The query fetching a product without a brand must count the nested ordering once"
			);

			addBrandOfFirstProduct();
			addCategoryOfFirstBrand();

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_CATEGORY, productWithBrandOrderedByCategoryName()),
				"The query fetching a product whose brand references a category must count the nested ordering once"
			);
		}

		@Test
		@DisplayName("A pick-first ordering by the referenced entity counts, with and without rows to order by")
		void shouldCountPickFirstOrderingByReferencedEntityWithAndWithoutRows() {
			// the live products hold rows of the categories, the archive holds no product at all - either way the
			// ordering plans a sorter over the categories in a context of its own
			assertEquals(
				Map.of(categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L),
				capabilitiesRequestedByFetching(
					ENTITY_CATEGORY,
					productsOrderedByCategoryName(Scope.LIVE, pickFirstByEntityProperty(categoryNameOrdering()))
				),
				"The live query ordering by the first category must count its name once"
			);
			assertEquals(
				Map.of(categoryNameKey(Capability.SORTABLE, Scope.ARCHIVED), 1L),
				capabilitiesRequestedByFetching(
					ENTITY_CATEGORY,
					productsOrderedByCategoryName(Scope.ARCHIVED, pickFirstByEntityProperty(categoryNameOrdering()))
				),
				"The archive query, with no product to order, must count the name of the category once"
			);
		}

		@Test
		@DisplayName("A traversal ordering by the referenced entity counts with rows to order by")
		void shouldCountTraversalOrderingByReferencedEntityWithRows() {
			// the categories are not hierarchical, so the traversal orders the categories the live products reference
			// by a sorter over the categories planned in a context of its own
			assertEquals(
				Map.of(categoryNameKey(Capability.SORTABLE, Scope.LIVE), 1L),
				capabilitiesRequestedByFetching(
					ENTITY_CATEGORY,
					productsOrderedByCategoryName(Scope.LIVE, traverseByEntityProperty(categoryNameOrdering()))
				),
				"The live query traversing the categories by their name must count it once"
			);
		}

		@Test
		@DisplayName("The orderings of a summary of one reference count the target's flags, with and without data")
		void shouldCountOrderingsOfReferenceSummaryWithAndWithoutData() {
			// the options of the brands and their groups, the tags, are ordered by their rank - the sorters are planned
			// while the query is planned, whether there is an option to order or not
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					referenceSummaryOfReference(
						REFERENCE_BRAND,
						FacetStatisticsDepth.COUNTS,
						(FilterGroupBy) null,
						orderBy(attributeNatural(ATTRIBUTE_RANK)),
						orderGroupBy(attributeNatural(ATTRIBUTE_RANK))
					)
				)
			);
			final Map<String, Map<SchemaCapabilityKey, Long>> expected = rankSortRequestedOn(ENTITY_BRAND, ENTITY_TAG);

			assertEquals(
				expected, capabilitiesRequestedOn(query, ENTITY_BRAND, ENTITY_TAG),
				"The orderings of the summary must count the rank of the brands and of the tags once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedOn(query, ENTITY_BRAND, ENTITY_TAG),
				"The orderings of the summary must count the rank of the brands and of the tags once, now that a " +
					"brand exists"
			);
		}

		@Test
		@DisplayName("The ordering of the statistics of the queried hierarchy counts, with and without data")
		void shouldCountOrderingOfHierarchyOfSelfWithAndWithoutData() {
			final Query query = Query.query(
				collection(ENTITY_BRAND),
				require(hierarchyOfSelf(orderBy(attributeNatural(ATTRIBUTE_RANK)), fromRoot("tree")))
			);

			assertCountedOnce(
				capabilitiesRequestedByFetching(ENTITY_BRAND, query), rankKey(Capability.SORTABLE),
				"The statistics of the brands without any brand must count the ordering once"
			);

			addBrandOfFirstProduct();

			assertCountedOnce(
				capabilitiesRequestedByFetching(ENTITY_BRAND, query), rankKey(Capability.SORTABLE),
				"The statistics of the brands must count the ordering once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The ordering of the statistics of a referenced hierarchy counts, with and without data")
		void shouldCountOrderingOfHierarchyOfReferenceWithAndWithoutData() {
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					hierarchyOfReference(REFERENCE_BRAND, orderBy(attributeNatural(ATTRIBUTE_RANK)), fromRoot("tree"))
				)
			);

			assertCountedOnce(
				capabilitiesRequestedByFetching(ENTITY_BRAND, query), rankKey(Capability.SORTABLE),
				"The statistics of the brands of the products without any brand must count the ordering once"
			);

			addBrandOfFirstProduct();

			assertCountedOnce(
				capabilitiesRequestedByFetching(ENTITY_BRAND, query), rankKey(Capability.SORTABLE),
				"The statistics of the brands of the products must count the ordering once, now that a brand exists"
			);
		}

		/**
		 * Builds the query fetching the first product with its brand ordered by the name of the first category the
		 * brand references - an ordering of the fetched references whose own translation plans another ordering.
		 *
		 * @return the query
		 */
		@Nonnull
		private static Query productWithBrandOrderedByCategoryName() {
			return Query.query(
				collection(ENTITY_PRODUCT),
				filterBy(entityPrimaryKeyInSet(1)),
				require(
					entityFetch(
						referenceContent(
							REFERENCE_BRAND,
							orderBy(
								entityProperty(
									referenceProperty(
										REFERENCE_CATEGORIES,
										pickFirstByEntityProperty(categoryNameOrdering()),
										entityProperty(entityPrimaryKeyNatural(OrderDirection.ASC))
									)
								)
							)
						)
					)
				)
			);
		}

		/**
		 * Makes the brand 1 reference the category 1 - after which the brand holds a row to order it by.
		 */
		private void addCategoryOfFirstBrand() {
			RequestedCapabilityAccumulationTest.this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.getEntity(ENTITY_BRAND, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(REFERENCE_CATEGORIES, 1)
						.upsertVia(session);
				}
			);
		}

	}

	@Nested
	@DisplayName("A constraint translated in the context of the query against the schema of another entity")
	class ConstraintsOfAnotherSchema {

		@Test
		@DisplayName("The parent filter of a referenced hierarchy counts on the referenced type, with and without data")
		void shouldCountParentFilterOfHierarchyWithinReferenceWithAndWithoutData() {
			// the parent filter selects among the brands, in the context of the query of the products - the label and
			// the tree it depends on are declared by the brands, and they are counted there whether a brand exists or
			// not
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				filterBy(hierarchyWithin(REFERENCE_BRAND, attributeEquals(ATTRIBUTE_LABEL, "label-1")))
			);
			final Map<SchemaCapabilityKey, Long> expected = Map.of(labelKey(), 1L, brandHierarchicalKey(), 1L);

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_BRAND, query),
				"The query of the products without any brand must count the parent filter and the tree once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_BRAND, query),
				"The query of the products must count the parent filter and the tree once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The parent filter of the queried hierarchy counts, with and without data")
		void shouldCountParentFilterOfHierarchyWithinSelfWithAndWithoutData() {
			final Query query = Query.query(
				collection(ENTITY_BRAND),
				filterBy(hierarchyWithinSelf(attributeEquals(ATTRIBUTE_LABEL, "label-1")))
			);
			final Map<SchemaCapabilityKey, Long> expected = Map.of(labelKey(), 1L, brandHierarchicalKey(), 1L);

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_BRAND, query),
				"The query of the brands without any brand must count the parent filter and the tree once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_BRAND, query),
				"The query of the brands must count the parent filter and the tree once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The node filter of a referenced hierarchy counts on the referenced type, with and without data")
		void shouldCountHavingFilterOfHierarchyWithinReferenceWithAndWithoutData() {
			// the `having` filter selects the nodes of the tree of the brands the products may sit in
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				filterBy(
					hierarchyWithin(
						REFERENCE_BRAND, entityPrimaryKeyInSet(1), having(attributeEquals(ATTRIBUTE_RANK, 1L))
					)
				)
			);
			final Map<SchemaCapabilityKey, Long> expected = Map.of(
				rankKey(Capability.FILTERABLE), 1L, brandHierarchicalKey(), 1L
			);

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_BRAND, query),
				"The query of the products without any brand must count the node filter and the tree once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_BRAND, query),
				"The query of the products must count the node filter and the tree once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The node filters of the statistics of a referenced hierarchy count, with and without data")
		void shouldCountNodeFiltersOfHierarchyOfReferenceWithAndWithoutData() {
			// the statistics start at the brand the `fromNode` filter selects and stop at those the `stopAt` filter
			// selects - both filters select among the brands, and both are planned while the query is planned
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					hierarchyOfReference(
						REFERENCE_BRAND,
						fromNode(
							"tree",
							node(filterBy(attributeEquals(ATTRIBUTE_LABEL, "label-1"))),
							stopAt(node(filterBy(attributeEquals(ATTRIBUTE_RANK, 1L))))
						)
					)
				)
			);

			final Map<SchemaCapabilityKey, Long> withoutBrand = capabilitiesRequestedByFetching(ENTITY_BRAND, query);
			assertCountedOnce(withoutBrand, labelKey(), "The `fromNode` filter without any brand must count once");
			assertCountedOnce(
				withoutBrand, rankKey(Capability.FILTERABLE), "The `stopAt` filter without any brand must count once"
			);

			addBrandOfFirstProduct();

			final Map<SchemaCapabilityKey, Long> withBrand = capabilitiesRequestedByFetching(ENTITY_BRAND, query);
			assertCountedOnce(withBrand, labelKey(), "The `fromNode` filter must count once, now that a brand exists");
			assertCountedOnce(
				withBrand, rankKey(Capability.FILTERABLE),
				"The `stopAt` filter must count once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("The entity filter of a segment counts, with and without data")
		void shouldCountEntityFilterOfSegmentWithAndWithoutData() {
			// the segment selects the products it orders by `code` - the filter of the query matching nothing leaves
			// the segment no product to select, and the query is answered by the empty plan
			assertCountedOnce(
				capabilitiesRequestedByFetching(ENTITY_PRODUCT, productsInSegmentOfCode(null)), CODE_FILTER,
				"The query of all products must count the filter of the segment once"
			);
			assertCountedOnce(
				capabilitiesRequestedByFetching(
					ENTITY_PRODUCT,
					productsInSegmentOfCode(
						filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(CATEGORY_COUNT + 1)))
					)
				),
				CODE_FILTER,
				"The query matching no product must count the filter of the segment once"
			);
		}

		@Test
		@DisplayName("The group selector of a histogram filter counts on the group type, with and without rows")
		void shouldCountGroupSelectorOfHistogramHavingWithAndWithoutRows() {
			// the group selector picks the category the histogram of the weighted tags is narrowed to - it is
			// resolved among the categories while the query is planned, whether a product holds a row of the reference
			// or not
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				filterBy(
					histogramHaving(
						REFERENCE_WEIGHTED_TAGS, HISTOGRAM_WEIGHT, 0L, 10L,
						groupHaving(attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1"))
					)
				)
			);
			final Map<SchemaCapabilityKey, Long> expected = Map.of(
				categoryNameKey(Capability.FILTERABLE, Scope.LIVE), 1L
			);

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_CATEGORY, query),
				"The query without any weighted tag must count the group selector once"
			);

			addWeightedTagOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedByFetching(ENTITY_CATEGORY, query),
				"The query must count the group selector once, now that a product holds a weighted tag"
			);
		}

		@Test
		@DisplayName("An ordering of the fetched references by their own attribute counts, with and without data")
		void shouldCountOrderingOfFetchedReferencesByReferenceAttributeWithAndWithoutData() {
			assertCountedOnce(
				capabilitiesRequestedByFetching(ENTITY_PRODUCT, productWithCategoriesOrderedByOrder(1)),
				ORDER_IN_CATEGORY_SORT,
				"The query fetching a product must count the ordering of its categories once"
			);
			assertCountedOnce(
				capabilitiesRequestedByFetching(
					ENTITY_PRODUCT, productWithCategoriesOrderedByOrder(PRODUCT_COUNT + 1)
				),
				ORDER_IN_CATEGORY_SORT,
				"The query fetching no product must count the ordering of the categories once"
			);
		}

		/**
		 * Returns the key of the hierarchy of the brands, indexed in the live scope.
		 *
		 * @return the key
		 */
		@Nonnull
		private static SchemaCapabilityKey brandHierarchicalKey() {
			return SchemaCapabilityKey.entity(ENTITY_BRAND, Capability.HIERARCHICAL, Scope.LIVE);
		}

		/**
		 * Builds the query of the products ordered by a segment of the products of one code, ordered by their
		 * priority.
		 *
		 * @param filterBy the filter of the query, NULL for none
		 * @return the query
		 */
		@Nonnull
		private static Query productsInSegmentOfCode(@Nullable FilterBy filterBy) {
			return Query.query(
				collection(ENTITY_PRODUCT),
				filterBy,
				orderBy(
					segments(
						segment(
							entityHaving(attributeEquals(ATTRIBUTE_CODE, "product-1")),
							orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC))
						)
					)
				)
			);
		}

		/**
		 * Builds the query fetching one product with its references to the categories ordered by the order of the
		 * product in the category.
		 *
		 * @param productPrimaryKey the product to fetch
		 * @return the query
		 */
		@Nonnull
		private static Query productWithCategoriesOrderedByOrder(int productPrimaryKey) {
			return Query.query(
				collection(ENTITY_PRODUCT),
				filterBy(entityPrimaryKeyInSet(productPrimaryKey)),
				require(
					entityFetch(
						referenceContent(
							REFERENCE_CATEGORIES,
							orderBy(attributeNatural(ATTRIBUTE_ORDER_IN_CATEGORY, OrderDirection.ASC))
						)
					)
				)
			);
		}

	}

	@Nested
	@DisplayName("The orderings of a summary of all references")
	class SummaryOfAllReferencesOrderings {

		@Test
		@DisplayName("The orderings of a summary of all references count each target's flag, with and without data")
		void shouldCountOrderingsOfSummaryOfAllReferencesOnceWithAndWithoutData() {
			// the options are ordered by the rank of the categories, which hold data, and of the brands, which hold
			// none at first; the groups by the rank of the tags, the groups of the brands - each of the orderings
			// requests the rank of its type, whether there is an option or a group to order or not
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					referenceSummary(
						FacetStatisticsDepth.COUNTS,
						orderBy(attributeNatural(ATTRIBUTE_RANK)),
						orderGroupBy(attributeNatural(ATTRIBUTE_RANK))
					)
				)
			);
			final Map<String, Map<SchemaCapabilityKey, Long>> expected = rankSortRequestedOn(SUMMARIZED_TYPES);

			assertEquals(
				expected, capabilitiesRequestedOn(query, SUMMARIZED_TYPES),
				"The orderings of the summary must count the rank of each summarized type once"
			);

			addBrandOfFirstProduct();

			assertEquals(
				expected, capabilitiesRequestedOn(query, SUMMARIZED_TYPES),
				"The orderings of the summary must count the rank of each summarized type once, now that a brand exists"
			);
		}

		@Test
		@DisplayName("An ordering of a summary of all references one target type cannot evaluate fails without data")
		void shouldRefuseOrderingOfSummaryOfAllReferencesNamingAttributeOneTargetLacksWithAndWithoutData() {
			// the name orders the categories, but the brands declare no such attribute - the summary of all references
			// orders the options of both, so the query is refused whether a brand option exists to order or not
			final Query query = Query.query(
				collection(ENTITY_PRODUCT),
				require(
					referenceSummary(
						FacetStatisticsDepth.COUNTS,
						orderBy(attributeNatural(ATTRIBUTE_CATEGORY_NAME)),
						(OrderGroupBy) null
					)
				)
			);

			assertThrows(
				AttributeNotFoundException.class,
				() -> executeQuery(query),
				"The ordering naming an attribute the brands lack must be refused before any brand exists"
			);

			addBrandOfFirstProduct();

			assertThrows(
				AttributeNotFoundException.class,
				() -> executeQuery(query),
				"The ordering naming an attribute the brands lack must be refused once a brand exists"
			);
		}

	}

	/**
	 * Plans one query in a context of its own and reports the request counts it moved on the collection's registry.
	 *
	 * @param query the query to plan
	 * @return the capabilities whose count the query moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> capabilitiesRequestedBy(@Nonnull Query query) {
		final Map<SchemaCapabilityKey, Long> before = requestedCounts();
		QueryPlanner.planQuery(planningContextFor(query));
		return requestedCountsSince(before);
	}

	/**
	 * Same reading as {@link #capabilitiesRequestedBy(Query)}, but for a query that goes through a real session and is
	 * actually executed - the only way to reach the debug modes, which execute the plans they verify.
	 *
	 * @param query the query to execute
	 * @return the capabilities whose count the query moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> capabilitiesRequestedByExecuting(@Nonnull Query query) {
		final Map<SchemaCapabilityKey, Long> before = requestedCounts();
		executeQuery(query);
		return requestedCountsSince(before);
	}

	/**
	 * Same reading as {@link #capabilitiesRequestedByExecuting(Query)}, but for a query whose entities are fetched with
	 * their bodies, and read on the registry of the passed collection - the one whose schema declares what the query
	 * or a nested query of it named.
	 *
	 * @param entityType the collection whose registry is read
	 * @param query      the query to execute
	 * @return the capabilities whose count the query moved on that registry, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> capabilitiesRequestedByFetching(
		@Nonnull String entityType,
		@Nonnull Query query
	) {
		final Map<SchemaCapabilityKey, Long> before = requestedCounts(entityType);
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryList(query, EntityClassifier.class);
			}
		);
		return requestedCountsSince(entityType, before);
	}

	/**
	 * Builds the query of the products of one category filtered by `code` and ordered by `priority`.
	 *
	 * @param categoryPrimaryKey the category the products must reference
	 * @return the query
	 */
	@Nonnull
	private static Query productsOfCategoryOrderedByPriority(int categoryPrimaryKey) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(
				and(
					attributeEquals(ATTRIBUTE_CODE, "product-3"),
					referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(categoryPrimaryKey))
				)
			),
			orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC))
		);
	}

	/**
	 * Builds the query of the categories of one scope filtered and ordered by their name.
	 *
	 * @param scope the scope to query
	 * @return the query
	 */
	@Nonnull
	private static Query categoriesByNameInScope(@Nonnull Scope scope) {
		return Query.query(
			collection(ENTITY_CATEGORY),
			filterBy(and(scope(scope), attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1"))),
			orderBy(attributeNatural(ATTRIBUTE_CATEGORY_NAME, OrderDirection.ASC))
		);
	}

	/**
	 * Builds the query fetching the first entity of one type and scope with its references to the categories ordered
	 * by the name of the category.
	 *
	 * @param entityType the type of the queried entity, declaring the reference {@link #REFERENCE_CATEGORIES}
	 * @param scope      the scope to query
	 * @return the query
	 */
	@Nonnull
	private static Query entityWithCategoriesOrderedByName(@Nonnull String entityType, @Nonnull Scope scope) {
		return Query.query(
			collection(entityType),
			filterBy(and(scope(scope), entityPrimaryKeyInSet(1))),
			require(
				entityFetch(
					referenceContent(
						REFERENCE_CATEGORIES,
						orderBy(entityProperty(attributeNatural(ATTRIBUTE_CATEGORY_NAME, OrderDirection.ASC)))
					)
				)
			)
		);
	}

	/**
	 * Builds the query fetching one product with both its references to the categories, each ordered by the name of the
	 * category.
	 *
	 * @param productPrimaryKey the product to fetch
	 * @return the query
	 */
	@Nonnull
	private static Query productWithBothCategoryReferencesOrderedByName(int productPrimaryKey) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(entityPrimaryKeyInSet(productPrimaryKey)),
			require(
				entityFetch(
					referenceContent(
						REFERENCE_CATEGORIES,
						orderBy(entityProperty(attributeNatural(ATTRIBUTE_CATEGORY_NAME, OrderDirection.ASC)))
					),
					referenceContent(
						REFERENCE_MAIN_CATEGORY,
						orderBy(entityProperty(attributeNatural(ATTRIBUTE_CATEGORY_NAME, OrderDirection.ASC)))
					)
				)
			)
		);
	}

	/**
	 * Builds the query fetching one product with its references to the categories filtered by the order of the
	 * product in the category.
	 *
	 * @param productPrimaryKey the product to fetch
	 * @return the query
	 */
	@Nonnull
	private static Query productWithCategoriesFilteredByOrder(int productPrimaryKey) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(entityPrimaryKeyInSet(productPrimaryKey)),
			require(
				entityFetch(
					referenceContent(
						REFERENCE_CATEGORIES, filterBy(attributeEquals(ATTRIBUTE_ORDER_IN_CATEGORY, 1L))
					)
				)
			)
		);
	}

	/**
	 * Builds the query fetching one product with its categories, fetched with their references to the tags filtered by
	 * the weight of the tag in the category.
	 *
	 * @param productPrimaryKey the product to fetch
	 * @return the query
	 */
	@Nonnull
	private static Query productWithCategoriesWithTagsFilteredByWeight(int productPrimaryKey) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(entityPrimaryKeyInSet(productPrimaryKey)),
			require(
				entityFetch(
					referenceContent(
						REFERENCE_CATEGORIES,
						entityFetch(
							referenceContent(REFERENCE_TAGS, filterBy(attributeEquals(ATTRIBUTE_WEIGHT, 1L)))
						)
					)
				)
			)
		);
	}

	/**
	 * Builds the query of the products of the passed scopes referencing a category named `category-1`.
	 *
	 * @param scopes the scopes of the products
	 * @return the query
	 */
	@Nonnull
	private static Query productsOfCategoryNamed(@Nonnull Scope... scopes) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(
				and(
					scope(scopes),
					referenceHaving(
						REFERENCE_CATEGORIES, entityHaving(attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1"))
					)
				)
			)
		);
	}

	/**
	 * Builds the query fetching one product of the live or the archived scope with its references to the categories
	 * filtered by the name of the category.
	 *
	 * @param productPrimaryKey the product to fetch
	 * @return the query
	 */
	@Nonnull
	private static Query productOfBothScopesWithCategoriesFilteredByName(int productPrimaryKey) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(and(scope(Scope.LIVE, Scope.ARCHIVED), entityPrimaryKeyInSet(productPrimaryKey))),
			require(
				entityFetch(
					referenceContent(
						REFERENCE_CATEGORIES,
						filterBy(entityHaving(attributeEquals(ATTRIBUTE_CATEGORY_NAME, "category-1")))
					)
				)
			)
		);
	}

	/**
	 * Returns the key of a capability of the name of the category.
	 *
	 * @param capability the flag
	 * @param scope      the scope
	 * @return the key
	 */
	@Nonnull
	private static SchemaCapabilityKey categoryNameKey(@Nonnull Capability capability, @Nonnull Scope scope) {
		return SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CATEGORY_NAME, capability, scope);
	}

	/**
	 * Returns the key of the filterable label of the live scope - the same key on the registry of each type declaring
	 * the label.
	 *
	 * @return the key
	 */
	@Nonnull
	private static SchemaCapabilityKey labelKey() {
		return SchemaCapabilityKey.entityAttribute(ATTRIBUTE_LABEL, Capability.FILTERABLE, Scope.LIVE);
	}

	/**
	 * Returns what a query whose summary filters the options and the groups of all references by the label requests
	 * on the summarized types: the label once on each of them.
	 *
	 * @return the requests, keyed by the type whose registry they land on
	 */
	@Nonnull
	private static Map<String, Map<SchemaCapabilityKey, Long>> labelRequestedOnEachSummarizedType() {
		final Map<String, Map<SchemaCapabilityKey, Long>> expected = new LinkedHashMap<>();
		for (final String entityType : SUMMARIZED_TYPES) {
			expected.put(entityType, Map.of(labelKey(), 1L));
		}
		return expected;
	}

	/**
	 * Returns the key of the rank of the live scope - the same key on the registry of each type declaring the rank.
	 *
	 * @param capability the flag of the rank
	 * @return the key
	 */
	@Nonnull
	private static SchemaCapabilityKey rankKey(@Nonnull Capability capability) {
		return SchemaCapabilityKey.entityAttribute(ATTRIBUTE_RANK, capability, Scope.LIVE);
	}

	/**
	 * Returns what a query ordering the passed types by their rank requests on them: the sortable rank once on each.
	 *
	 * @param entityTypes the ordered types
	 * @return the requests, keyed by the type whose registry they land on
	 */
	@Nonnull
	private static Map<String, Map<SchemaCapabilityKey, Long>> rankSortRequestedOn(@Nonnull String... entityTypes) {
		final Map<String, Map<SchemaCapabilityKey, Long>> expected = new LinkedHashMap<>();
		for (final String entityType : entityTypes) {
			expected.put(entityType, Map.of(rankKey(Capability.SORTABLE), 1L));
		}
		return expected;
	}

	/**
	 * Returns the ordering of the categories by their name.
	 *
	 * @return the ordering
	 */
	@Nonnull
	private static OrderConstraint categoryNameOrdering() {
		return attributeNatural(ATTRIBUTE_CATEGORY_NAME, OrderDirection.ASC);
	}

	/**
	 * Builds the query of the products of one scope ordered by the categories they reference, the categories ordered
	 * among themselves by the passed specification.
	 *
	 * @param scope         the scope to query
	 * @param specification the `pickFirstByEntityProperty` or `traverseByEntityProperty` ordering the categories
	 * @return the query
	 */
	@Nonnull
	private static Query productsOrderedByCategoryName(@Nonnull Scope scope, @Nonnull OrderConstraint specification) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(scope(scope)),
			orderBy(
				referenceProperty(
					REFERENCE_CATEGORIES, specification, entityProperty(entityPrimaryKeyNatural(OrderDirection.ASC))
				)
			)
		);
	}

	/**
	 * Builds the query of the products filtered by an entity filter of a reference, summarizing the options of the same
	 * reference filtered by the same attribute of the referenced entity.
	 *
	 * @param referenceName the reference of the product
	 * @param attributeName the attribute of the referenced entity
	 * @param value         the value of the attribute
	 * @return the query
	 */
	@Nonnull
	private static Query productsFilteredAndSummarizedByTarget(
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nonnull String value
	) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(referenceHaving(referenceName, entityHaving(attributeEquals(attributeName, value)))),
			require(
				referenceSummaryOfReference(
					referenceName, FacetStatisticsDepth.COUNTS, filterBy(attributeEquals(attributeName, value))
				)
			)
		);
	}

	/**
	 * Builds the query of the products filtered by an entity filter of a reference, fetching the references filtered by
	 * the same entity filter.
	 *
	 * @param referenceName the reference of the product
	 * @param attributeName the attribute of the referenced entity
	 * @param value         the value of the attribute
	 * @return the query
	 */
	@Nonnull
	private static Query productsFilteredAndFetchedByTarget(
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nonnull String value
	) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(referenceHaving(referenceName, entityHaving(attributeEquals(attributeName, value)))),
			require(
				entityFetch(
					referenceContent(referenceName, filterBy(entityHaving(attributeEquals(attributeName, value))))
				)
			)
		);
	}

	/**
	 * Builds the query of the products filtered by two entity filters of a reference naming the same attribute of the
	 * referenced entity with different values.
	 *
	 * @param referenceName the reference of the product
	 * @param attributeName the attribute of the referenced entity
	 * @param value         the value of the attribute the first filter asks for
	 * @param otherValue    the value of the attribute the second filter asks for
	 * @return the query
	 */
	@Nonnull
	private static Query productsFilteredTwiceByTarget(
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nonnull String value,
		@Nonnull String otherValue
	) {
		return Query.query(
			collection(ENTITY_PRODUCT),
			filterBy(
				or(
					referenceHaving(referenceName, entityHaving(attributeEquals(attributeName, value))),
					referenceHaving(referenceName, entityHaving(attributeEquals(attributeName, otherValue)))
				)
			)
		);
	}

	/**
	 * Adds the brand 1, labelled `label-1`, and makes the first product reference it in the group of the tag 1 - after
	 * which the brands hold data and the reference of the products to them has an option and a group to summarize.
	 */
	private void addBrandOfFirstProduct() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_BRAND, 1).setAttribute(ATTRIBUTE_LABEL, "label-1"));
				session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_BRAND, 1, whichIs -> whichIs.setGroup(1))
					.upsertVia(session);
			}
		);
	}

	/**
	 * Makes the first product reference the category 1 by {@link #REFERENCE_MAIN_CATEGORY} - after which the product
	 * holds a row of both its references to the categories.
	 */
	private void addMainCategoryOfFirstProduct() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_MAIN_CATEGORY, 1)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Makes the first product reference the tag 1 by {@link #REFERENCE_WEIGHTED_TAGS} in the group of the category 1,
	 * weighing 3 - after which the reference has a histogram to compute.
	 */
	private void addWeightedTagOfFirstProduct() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_WEIGHTED_TAGS, 1, whichIs -> whichIs.setGroup(1).setAttribute(ATTRIBUTE_WEIGHT, 3L)
					)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Declares the reflection {@link #REFERENCE_REFLECTED_STOCKS} on the products before the reference of the stocks it
	 * mirrors exists, runs the query in the same session - while the reflection is not attached - and asserts it is
	 * answered. The reference the reflection mirrors is declared at last, so that the session closes over a valid
	 * schema.
	 *
	 * @param reflection what the reflection states on its own rather than inherits
	 * @param query      the query that must be answered
	 */
	private void assertAnsweredWhileReflectionIsNotAttached(
		@Nonnull Consumer<ReflectedReferenceSchemaBuilder> reflection,
		@Nonnull Query query
	) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTED_STOCKS, ENTITY_STOCK, REFERENCE_PRODUCTS, reflection
					)
					.updateVia(session);
				final ReferenceSchemaContract reflected = session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
					.getReferenceOrThrowException(REFERENCE_REFLECTED_STOCKS);
				assertTrue(
					reflected instanceof ReflectedReferenceSchemaContract reflectedReference
						&& !reflectedReference.isReflectedReferenceAvailable(),
					"The premise is a reflection not attached yet"
				);

				assertDoesNotThrow(
					() -> session.queryList(query, EntityReference.class),
					"A valid query must be answered while a reflection of the queried type is not attached yet"
				);

				session.defineEntitySchema(ENTITY_STOCK)
					.withReferenceToEntity(
						REFERENCE_PRODUCTS, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedInScope(Scope.LIVE)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Same reading as {@link #capabilitiesRequestedByFetching(String, Query)}, on the registries of several
	 * collections at once.
	 *
	 * @param query       the query to execute
	 * @param entityTypes the collections whose registries are read
	 * @return the capabilities whose count the query moved on each registry, and by how much, keyed by the type
	 */
	@Nonnull
	private Map<String, Map<SchemaCapabilityKey, Long>> capabilitiesRequestedOn(
		@Nonnull Query query,
		@Nonnull String... entityTypes
	) {
		final Map<String, Map<SchemaCapabilityKey, Long>> before = new LinkedHashMap<>();
		for (final String entityType : entityTypes) {
			before.put(entityType, requestedCounts(entityType));
		}
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryList(query, EntityClassifier.class);
			}
		);
		final Map<String, Map<SchemaCapabilityKey, Long>> result = new LinkedHashMap<>();
		for (final String entityType : entityTypes) {
			result.put(entityType, requestedCountsSince(entityType, before.get(entityType)));
		}
		return result;
	}

	/**
	 * Executes one query the way a client would.
	 *
	 * @param query the query to execute
	 */
	private void executeQuery(@Nonnull Query query) {
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryList(query, EntityReference.class);
			}
		);
	}

	/**
	 * Reads every request count the collection's registry currently holds.
	 *
	 * @return the counts, keyed by capability
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCounts() {
		return requestedCounts(ENTITY_PRODUCT);
	}

	/**
	 * Reads every request count the registry of the passed collection currently holds.
	 *
	 * @param entityType the collection whose registry is read
	 * @return the counts, keyed by capability
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCounts(@Nonnull String entityType) {
		final Map<SchemaCapabilityKey, Long> result = new HashMap<>();
		for (final UsageEntry entry : entityCollection(entityType).getUsageRegistry().listUsages()) {
			result.put(entry.key(), entry.usage().getRequestedCount());
		}
		return result;
	}

	/**
	 * Reports how much each request count has moved since the snapshot was taken, dropping the ones that did not move.
	 *
	 * A difference rather than an absolute reading, so that neither the fixture's own writes nor a query an earlier
	 * case in the same method ran can be mistaken for the query under test.
	 *
	 * @param before counts read before the query ran
	 * @return the capabilities whose count moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCountsSince(@Nonnull Map<SchemaCapabilityKey, Long> before) {
		return requestedCountsSince(ENTITY_PRODUCT, before);
	}

	/**
	 * Reports how much each request count of the registry of the passed collection has moved since the snapshot was
	 * taken, dropping the ones that did not move.
	 *
	 * @param entityType the collection whose registry is read
	 * @param before     counts read before the query ran
	 * @return the capabilities whose count moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCountsSince(
		@Nonnull String entityType,
		@Nonnull Map<SchemaCapabilityKey, Long> before
	) {
		final Map<SchemaCapabilityKey, Long> result = new LinkedHashMap<>();
		for (final UsageEntry entry : entityCollection(entityType).getUsageRegistry().listUsages()) {
			final long delta = entry.usage().getRequestedCount() - before.getOrDefault(entry.key(), 0L);
			if (delta != 0L) {
				result.put(entry.key(), delta);
			}
		}
		return result;
	}

	/**
	 * Counts the candidate index sets the planner would cost for the query - the multiplier every deduplication case
	 * needs to be greater than one to prove anything.
	 *
	 * Run against a context of its own, so the index selection it performs cannot disturb the context the assertions
	 * are made on.
	 *
	 * @param query the query to analyse
	 * @return how many interchangeable index sets its filter offers
	 */
	private int candidatePlanCountOf(@Nonnull Query query) {
		final QueryPlanningContext context = planningContextFor(query);
		final IndexSelectionVisitor visitor = new IndexSelectionVisitor(context);
		context.getFilterBy().accept(visitor);
		return visitor.getTargetIndexes().size();
	}

	/**
	 * Builds a planning context for the query exactly as the session would, against the live product collection.
	 *
	 * The session is a mock: planning reads the catalog, the collection and its indexes, and touches the session only
	 * through paths that degrade gracefully without one. What matters for these tests is that the *collection* - and
	 * therefore its usage registry - is the real one.
	 *
	 * @param query the query to plan
	 * @return a fresh context, having collected nothing yet
	 */
	@Nonnull
	private QueryPlanningContext planningContextFor(@Nonnull Query query) {
		return productCollection().createQueryContext(
			new EvitaRequest(
				query.normalizeQuery(),
				OffsetDateTime.now(),
				EntityReference.class,
				null
			),
			Mockito.mock(EvitaSession.class)
		);
	}

	/**
	 * Asserts one logical query moved the capability's request count by exactly one.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must have moved
	 */
	private static void assertRequested(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key
	) {
		assertEquals(
			1L, (long) requested.getOrDefault(key, 0L),
			"The capability " + key + " must be counted exactly once per logical query, whatever the planner did " +
				"on the way there: " + requested
		);
	}

	/**
	 * Asserts the query left the capability's request count where it was.
	 *
	 * Deliberately a statement about the count rather than about the registry holding an entry at all: the update side
	 * resolves holders of its own for every element a write touches, so the presence of an entry proves nothing about
	 * what a query asked for.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must not have moved
	 * @param message   what it means if it did
	 */
	private static void assertNotRequested(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key,
		@Nonnull String message
	) {
		assertEquals(0L, (long) requested.getOrDefault(key, 0L), message + ": " + requested);
	}

	/**
	 * Asserts one logical query moved the capability's request count by exactly one, whatever else it moved.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must have moved
	 * @param message   what it means if it did not
	 */
	private static void assertCountedOnce(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key,
		@Nonnull String message
	) {
		assertEquals(1L, (long) requested.getOrDefault(key, 0L), message + ": " + requested);
	}

	/**
	 * Reads the holder the collection's registry keeps for the key, asserting on the way that the registry hands the
	 * same instance back on every resolve - the identity the accumulator's deduplication rests on.
	 *
	 * @param key the capability
	 * @return its holder
	 */
	@Nonnull
	private SchemaCapabilityUsage holderOf(@Nonnull SchemaCapabilityKey key) {
		final SchemaCapabilityUsageRegistry registry = productCollection().getUsageRegistry();
		final SchemaCapabilityUsage holder = registry.resolve(key);
		assertSame(holder, registry.resolve(key), "The registry hands out a different holder for the same key");
		return holder;
	}

	/**
	 * Reads the per-referenced-entity index covering one category, whose {@link io.evitadb.index.IndexActivity} tells
	 * whether the planner chose it.
	 *
	 * @param categoryPrimaryKey the category the index is bound to
	 * @return the index
	 */
	@Nonnull
	private EntityIndex reducedIndexOfCategory(int categoryPrimaryKey) {
		final EntityIndex index = productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, categoryPrimaryKey))
			)
		);
		assertNotNull(index, "No per-referenced-entity index covers category " + categoryPrimaryKey);
		return index;
	}

	/**
	 * Reads the collection's global index - the one every candidate plan not built on a reduced index uses.
	 *
	 * @return the index
	 */
	@Nonnull
	private EntityIndex globalIndex() {
		final EntityIndex index = productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		assertNotNull(index, "The product collection holds no global index");
		return index;
	}

	/**
	 * Looks the live product collection up behind the public API - the registry it holds is engine-internal state.
	 *
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection productCollection() {
		return entityCollection(ENTITY_PRODUCT);
	}

	/**
	 * Looks a collection up behind the public API - the registry it holds is engine-internal state.
	 *
	 * @param entityType the type of the collection
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection entityCollection(@Nonnull String entityType) {
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG))
			.getCollectionForEntityInternal(entityType)
			.orElseThrow(
				() -> new AssertionError("Catalog `" + CATALOG + "` holds no collection `" + entityType + "`")
			);
	}

	/**
	 * Wraps a filter in the collection header every case here shares.
	 *
	 * @param filterBy the filter
	 * @return the query
	 */
	@Nonnull
	private static Query query(@Nonnull FilterBy filterBy) {
		return Query.query(collection(ENTITY_PRODUCT), filterBy);
	}

	/**
	 * Wraps a filter and an ordering in the collection header every case here shares.
	 *
	 * @param filterBy the filter
	 * @param orderBy  the ordering
	 * @return the query
	 */
	@Nonnull
	private static Query query(@Nonnull FilterBy filterBy, @Nonnull OrderBy orderBy) {
		return Query.query(collection(ENTITY_PRODUCT), filterBy, orderBy);
	}

	/**
	 * Builds a fixture with several candidate index sets to plan against and one element of every kind the query side
	 * can request: an entity attribute filtered on, one ordered by, a sortable compound, a reference attribute usable
	 * both ways, and a filterable attribute nothing ever names. The categories are live only, and the only stock is
	 * archived and references a category - so a query over the archived categories, or a nested query of the archived
	 * stock over them, matches nothing because the scope holds no data. The categories reference the tags, the first of
	 * them tag 1 with the weight 1. The hierarchical brands hold no entity at all; they reference the categories, and
	 * the products reference them by a reference no product holds a row of, grouped by the tags - see
	 * {@link #addBrandOfFirstProduct()} for the case adding one. The categories, the brands and the tags share the
	 * filterable attribute {@link #ATTRIBUTE_LABEL} and the filterable and sortable attribute {@link #ATTRIBUTE_RANK},
	 * which no entity sets. The products declare two more references no
	 * product holds a row of: {@link #REFERENCE_MAIN_CATEGORY} to the categories, and {@link #REFERENCE_WEIGHTED_TAGS}
	 * to the tags grouped by the categories, bucketed but not faceted.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TAG)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(
						ATTRIBUTE_RANK, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, 1));
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_CATEGORY_NAME, String.class,
						thatIs -> thatIs.filterableInScope(Scope.values()).sortableInScope(Scope.values())
					)
					.withAttribute(
						ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(
						ATTRIBUTE_RANK, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					)
					.withReferenceToEntity(
						REFERENCE_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedInScope(Scope.LIVE)
							.withAttribute(
								ATTRIBUTE_WEIGHT, Long.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
							)
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_STOCK)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_STOCK, ARCHIVED_STOCK).setReference(REFERENCE_CATEGORIES, 1)
				);
				session.archiveEntity(ENTITY_STOCK, ARCHIVED_STOCK);
				session.defineEntitySchema(ENTITY_BRAND)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withAttribute(
						ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
					)
					.withAttribute(
						ATTRIBUTE_RANK, Long.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedInScope(Scope.LIVE)
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_CODE, String.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
					)
					.withAttribute(ATTRIBUTE_PRIORITY, Long.class, thatIs -> thatIs.sortableInScope(Scope.LIVE))
					.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
					.withSortableAttributeCompound(
						COMPOUND_CODE_WITH_PRIORITY,
						new AttributeElement[]{
							AttributeElement.attributeElement(ATTRIBUTE_CODE),
							AttributeElement.attributeElement(ATTRIBUTE_PRIORITY)
						},
						thatIs -> thatIs.indexedInScope(Scope.LIVE)
					)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							// indexed in both scopes so that a query may legally name the archive, but faceted in the
							// live scope only - which is what lets a case naming both scopes tell the scope that
							// declares the flag apart from the one that does not
							.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
							.facetedInScope(Scope.LIVE)
							.withAttribute(
								ATTRIBUTE_ORDER_IN_CATEGORY, Long.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
							)
					)
					.withReferenceToEntity(
						REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedInScope(Scope.LIVE)
							.facetedInScope(Scope.LIVE)
							.withGroupTypeRelatedToEntity(ENTITY_TAG)
					)
					.withReferenceToEntity(REFERENCE_MAIN_CATEGORY, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE)
					.withReferenceToEntity(
						REFERENCE_WEIGHTED_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(ENTITY_CATEGORY)
							.withAttribute(
								ATTRIBUTE_WEIGHT, Long.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).nullable()
							)
							.bucketed(
								HISTOGRAM_WEIGHT,
								ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_WEIGHT + "']")
							)
					)
					.updateVia(session);
				for (int i = 1; i <= CATEGORY_COUNT; i++) {
					final EntityBuilder category = session.createNewEntity(ENTITY_CATEGORY, i)
						.setAttribute(ATTRIBUTE_CATEGORY_NAME, "category-" + i);
					if (i == 1) {
						category.setReference(REFERENCE_TAGS, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_WEIGHT, 1L));
					}
					session.upsertEntity(category);
				}
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					final int productPrimaryKey = i;
					final int categoryPrimaryKey = ((i - 1) % CATEGORY_COUNT) + 1;
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, productPrimaryKey)
							.setAttribute(ATTRIBUTE_CODE, "product-" + productPrimaryKey)
							.setAttribute(ATTRIBUTE_PRIORITY, (long) productPrimaryKey)
							.setAttribute(ATTRIBUTE_EAN, "ean-" + productPrimaryKey)
							.setReference(
								REFERENCE_CATEGORIES, categoryPrimaryKey,
								whichIs -> whichIs.setAttribute(
									ATTRIBUTE_ORDER_IN_CATEGORY, (long) productPrimaryKey
								)
							)
					);
				}
			}
		);
	}

	/**
	 * Builds the configuration of the embedded instance this test runs against.
	 *
	 * @return configuration rooted at this test's directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
