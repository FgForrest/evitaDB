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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityKey.Capability;
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
import java.time.OffsetDateTime;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * **The accumulator is read by draining it, which is what the flush will later do.** Until the flush exists, draining
 * from the test is the only way to see what one logical query collected; once
 * {@link QueryPlanBuilder#build()} drains it, these readings move to the holders' counts and the drain here returns
 * nothing.
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
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_EAN = "ean";
	private static final String ATTRIBUTE_ORDER_IN_CATEGORY = "orderInCategory";
	private static final String COMPOUND_CODE_WITH_PRIORITY = "codeWithPriority";
	private static final int CATEGORY_COUNT = 4;
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int PRODUCT_COUNT = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;
	/** The category every multi-candidate query names, so the planner builds a plan around its own index. */
	private static final int QUERIED_CATEGORY = 2;

	/** Filtering by `code` - the capability most cases assert on. */
	private static final SchemaCapabilityKey CODE_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
	);
	/** Ordering by `priority`. */
	private static final SchemaCapabilityKey PRIORITY_SORT = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_PRIORITY, Capability.SORT, Scope.LIVE
	);
	/** Ordering by the compound - the key an attribute of the same name would be indistinguishable from. */
	private static final SchemaCapabilityKey COMPOUND_SORT = SchemaCapabilityKey.sortableCompound(
		null, COMPOUND_CODE_WITH_PRIORITY, Scope.LIVE
	);
	/** Filtering by an attribute the `categories` reference declares. */
	private static final SchemaCapabilityKey ORDER_IN_CATEGORY_FILTER = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_CATEGORIES, ATTRIBUTE_ORDER_IN_CATEGORY, Capability.FILTER, Scope.LIVE
	);
	/** Ordering by an attribute the `categories` reference declares. */
	private static final SchemaCapabilityKey ORDER_IN_CATEGORY_SORT = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_CATEGORIES, ATTRIBUTE_ORDER_IN_CATEGORY, Capability.SORT, Scope.LIVE
	);
	/** The control: a filterable attribute of the same shape as `code` that no query below ever names. */
	private static final SchemaCapabilityKey EAN_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
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

			final List<SchemaCapabilityUsage> requested = capabilitiesRequestedBy(query);

			assertEquals(
				2, requested.size(),
				"One logical query must contribute one entry per capability it names - a longer list means the " +
					"accumulator is counting candidate plans: " + describe(requested)
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

			final List<SchemaCapabilityUsage> requested = capabilitiesRequestedBy(query);

			assertEquals(
				1, requested.size(),
				"Repeating a constraint must not repeat the request: " + describe(requested)
			);
			assertRequested(requested, CODE_FILTER);
		}

		@Test
		@DisplayName("Draining twice yields nothing the second time, so a re-built plan counts nothing")
		void shouldLeaveNothingBehindAfterDraining() {
			// the guard the verification debug modes need: they build the preferred plan a second time, and a drain
			// that left the list in place would count that one logical query twice
			final QueryPlanningContext context = planningContextFor(
				query(filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3")))
			);
			QueryPlanner.planQuery(context);

			assertFalse(
				context.drainRequestedCapabilities().isEmpty(),
				"The first drain must hand over what was collected"
			);
			assertTrue(
				context.drainRequestedCapabilities().isEmpty(),
				"The accumulator was not emptied by the drain - a second plan build would count the same query again"
			);
		}

		@Test
		@DisplayName("A query naming no attribute at all accumulates nothing")
		void shouldAccumulateNothingForAQueryThatNamesNoAttribute() {
			final List<SchemaCapabilityUsage> requested = capabilitiesRequestedBy(
				query(filterBy(entityPrimaryKeyInSet(1, 2, 3)))
			);

			assertTrue(requested.isEmpty(), "Nothing was named, so nothing may be requested: " + describe(requested));
		}

	}

	@Nested
	@DisplayName("Which element the request lands on")
	class Attribution {

		@Test
		@DisplayName("A sortable compound is counted as the compound, not as an attribute of the same name")
		void shouldAccumulateTheCompoundUnderItsOwnKind() {
			final List<SchemaCapabilityUsage> requested = capabilitiesRequestedBy(
				query(
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					orderBy(attributeNatural(COMPOUND_CODE_WITH_PRIORITY, OrderDirection.ASC))
				)
			);

			assertRequested(requested, COMPOUND_SORT);
			assertNotRequested(
				requested,
				SchemaCapabilityKey.entityAttribute(COMPOUND_CODE_WITH_PRIORITY, Capability.SORT, Scope.LIVE),
				"The compound was recorded as if it were an attribute, which pools it with an attribute that may " +
					"legitimately carry the same name"
			);
		}

		@Test
		@DisplayName("A reference attribute is counted on its reference, not on the entity")
		void shouldAccumulateAReferenceAttributeUnderItsReference() {
			final List<SchemaCapabilityUsage> filtering = capabilitiesRequestedBy(
				query(
					filterBy(
						referenceHaving(REFERENCE_CATEGORIES, attributeEquals(ATTRIBUTE_ORDER_IN_CATEGORY, 1L))
					)
				)
			);
			assertRequested(filtering, ORDER_IN_CATEGORY_FILTER);
			assertNotRequested(
				filtering,
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER_IN_CATEGORY, Capability.FILTER, Scope.LIVE),
				"The reference's attribute was recorded as an attribute of the entity, which pools two elements the " +
					"schema keeps apart"
			);

			final List<SchemaCapabilityUsage> ordering = capabilitiesRequestedBy(
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
		}

		@Test
		@DisplayName("An attribute no query names is not requested by one that names its neighbours")
		void shouldNotRequestACapabilityTheQueryNeverNames() {
			final List<SchemaCapabilityUsage> requested = capabilitiesRequestedBy(
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
			final QueryPlanningContext context = planningContextFor(query);
			QueryPlanner.planQuery(context);

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
			assertRequested(context.drainRequestedCapabilities(), CODE_FILTER);
		}

	}

	/**
	 * Plans one query in a context of its own and hands over everything that context collected.
	 *
	 * @param query the query to plan
	 * @return the holders the query requested, deduplicated as the accumulator deduplicates them
	 */
	@Nonnull
	private List<SchemaCapabilityUsage> capabilitiesRequestedBy(@Nonnull Query query) {
		final QueryPlanningContext context = planningContextFor(query);
		QueryPlanner.planQuery(context);
		return context.drainRequestedCapabilities();
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
	 * Asserts the capability is among the ones the query requested, exactly once, and that the holder is the very one
	 * the collection's registry keeps for that key - which is what makes the flush land on the right counter.
	 *
	 * @param requested what the query collected
	 * @param key       the capability that must be in it
	 */
	private void assertRequested(
		@Nonnull List<SchemaCapabilityUsage> requested,
		@Nonnull SchemaCapabilityKey key
	) {
		final SchemaCapabilityUsage holder = holderOf(key);
		int occurrences = 0;
		for (final SchemaCapabilityUsage candidate : requested) {
			if (candidate == holder) {
				occurrences++;
			}
		}
		assertEquals(
			1, occurrences,
			"The capability " + key + " was requested " + occurrences + " times by one logical query, expected once: " +
				describe(requested)
		);
	}

	/**
	 * Asserts the capability is **not** among the ones the query requested.
	 *
	 * Deliberately a statement about the query's accumulator rather than about the registry holding an entry: the
	 * update side resolves holders of its own for every element a write touches, so the presence of an entry proves
	 * nothing about what a query asked for.
	 *
	 * @param requested what the query collected
	 * @param key       the capability that must not be in it
	 * @param message   what it means if it is
	 */
	private void assertNotRequested(
		@Nonnull List<SchemaCapabilityUsage> requested,
		@Nonnull SchemaCapabilityKey key,
		@Nonnull String message
	) {
		final SchemaCapabilityUsage holder = holderOf(key);
		for (final SchemaCapabilityUsage candidate : requested) {
			assertFalse(candidate == holder, message + ": " + describe(requested));
		}
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
	 * Renders what a query collected as the keys it stands for, so a failing assertion names the capabilities rather
	 * than a list of holder identities.
	 *
	 * @param requested the holders a query collected
	 * @return a readable description
	 */
	@Nonnull
	private String describe(@Nonnull List<SchemaCapabilityUsage> requested) {
		final StringBuilder result = new StringBuilder(128);
		result.append('[');
		for (final UsageEntry entry : productCollection().getUsageRegistry().listUsages()) {
			for (final SchemaCapabilityUsage holder : requested) {
				if (holder == entry.usage()) {
					if (result.length() > 1) {
						result.append(", ");
					}
					result.append(entry.key());
				}
			}
		}
		return result.append(']').toString();
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
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG))
			.getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow(
				() -> new AssertionError("Catalog `" + CATALOG + "` holds no collection `" + ENTITY_PRODUCT + "`")
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
	 * both ways, and a filterable attribute nothing ever names.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
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
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_ORDER_IN_CATEGORY, Long.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
							)
					)
					.updateVia(session);
				for (int i = 1; i <= CATEGORY_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
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
