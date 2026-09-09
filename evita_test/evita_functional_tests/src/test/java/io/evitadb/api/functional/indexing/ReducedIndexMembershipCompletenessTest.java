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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

/**
 * Attacks the **quantifier** behind {@link ReducedIndexMembership}, not the mechanism.
 *
 * The reverse lookup that `ReevaluateExpressionExecutor#resolveSiblingReducedIndexes` consults instead of
 * walking every reduced index of a collection rests on one claim: *every* boundary at which an owner enters
 * or leaves a reduced index updates it. A test that drives a few upserts and then reads a facet back
 * demonstrates the mechanism working; it says nothing about the quantifier, and would pass unchanged while an
 * entire class of boundary went unhooked. That is the failure this class exists to catch.
 *
 * So every assertion here is made against **ground truth** — the reduced indexes themselves, read back from
 * the collection — and never against the map's own bookkeeping. {@link #assertMembershipMatchesIndexes} is the
 * whole point of the class:
 *
 * - the indexes a reference advertises are exactly `covered ∪ residual`, so none can be silently skipped;
 * - every covered index's entries name exactly its members, in both directions;
 * - `coveredOwners` is exactly the key set of the lookup, since the walk narrows the affected set with it and
 *   an owner missing from it is an owner the trigger will never consider;
 * - no covered index holds more owners than the threshold allows, which is what bounds the memory.
 *
 * It runs after each of the boundary classes in turn: bulk load, a later join, a departure, an entity removed
 * outright, a threshold crossing in each direction, and a catalog reloaded from disk — the last because the
 * lookup is derived state that is never persisted, and a reload that failed to rebuild it would leave a
 * collection permanently unaccelerated (or, worse, half-accelerated) with nothing else to show for it.
 *
 * {@link #referenceRaisedToPartitionedStillRegistersOwners} covers the other half of the contract, and the
 * half that would be a wrong answer rather than a slow one: the lookup is an **accelerator, never an
 * authority**, so a reference whose indexes it does not know must still be walked in full. That is exactly
 * what a `ReferenceIndexType` raised on an already-populated collection produces — the engine does not rebuild
 * indexes for a schema change (issue #409) — and the facet must still reach every index it owes.
 *
 * @author Claude (issue #1529 sibling-resolver optimization), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reduced-index membership — completeness against the indexes themselves")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(FACET)
class ReducedIndexMembershipCompletenessTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	private static final String REF_CATEGORIES = "categories";
	private static final String REF_BRAND = "brand";
	private static final String REF_PARAMETER_VALUES = "parameterValues";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";

	private static final int PARAMETER_PK = 100;
	private static final int PARAM_VALUE_PK = 10;
	private static final int BRAND_PK = 7;

	/**
	 * Comfortably more than `ReducedIndexMembership.DEFAULT_COVERAGE_THRESHOLD`, so a category can be pushed
	 * over the coverage boundary and pulled back under it within one test.
	 */
	private static final int OVER_THRESHOLD = 25;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReducedIndexMembershipCompletenessTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("bulk load leaves the lookup matching every reduced index")
	void bulkLoadMatches(CatalogState state) {
		prepare(state, "CHECKBOX");
		upsertProducts(1, 12, 1);
		assertMembershipMatchesIndexes();
		assertMembershipEngaged(REF_CATEGORIES);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner joining a reduced index later is recorded")
	void ownerJoiningLaterIsRecorded(CatalogState state) {
		prepare(state, "CHECKBOX");
		upsertProducts(1, 5, 1);
		assertMembershipMatchesIndexes();
		// the owner joins a second category it was not a member of
		tx(session -> session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REF_CATEGORIES, 2)
			.upsertVia(session));
		assertMembershipMatchesIndexes();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an owner leaving a reduced index is forgotten")
	void ownerLeavingIsForgotten(CatalogState state) {
		prepare(state, "CHECKBOX");
		upsertProducts(1, 5, 1);
		tx(session -> session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_CATEGORIES, 1)
			.upsertVia(session));
		assertMembershipMatchesIndexes();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("removing the entity outright leaves nothing behind")
	void entityRemovalIsForgotten(CatalogState state) {
		prepare(state, "CHECKBOX");
		upsertProducts(1, 5, 1);
		tx(session -> session.deleteEntity(ENTITY_PRODUCT, 3));
		assertMembershipMatchesIndexes();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("crossing the coverage threshold in both directions keeps the lookup exact")
	void thresholdCrossingBothWaysMatches(CatalogState state) {
		prepare(state, "CHECKBOX");
		// one category grows past the coverage threshold - it must leave coverage for the walk
		upsertProducts(1, OVER_THRESHOLD, 1);
		assertMembershipMatchesIndexes();
		assertTrue(
			residualOf(REF_CATEGORIES).size() > 0,
			"a category of " + OVER_THRESHOLD + " owners must have been promoted out of coverage"
		);
		// ... and shrinks back far enough to be demoted into coverage again
		for (int pk = 2; pk <= OVER_THRESHOLD; pk++) {
			final int productPk = pk;
			tx(session -> session.deleteEntity(ENTITY_PRODUCT, productPk));
		}
		assertMembershipMatchesIndexes();
	}

	@Test
	@DisplayName("a catalog reloaded from disk rebuilds the lookup it never persisted")
	void reloadRebuildsTheLookup() {
		prepare(CatalogState.ALIVE, "CHECKBOX");
		upsertProducts(1, 12, 1);
		assertMembershipMatchesIndexes();

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		// the catalog is loaded on the service pool, so `new Evita` returns while it is still BEING_ACTIVATED
		awaitCatalogLoaded();

		assertMembershipMatchesIndexes();
		assertMembershipEngaged(REF_CATEGORIES);
		assertTrue(
			coveredOf(REF_CATEGORIES).size() > 0,
			"a reloaded collection must regain COVERAGE, not merely a residual list - the load-time build "
				+ "resolves each index and decides coverage, unlike the seeding maintenance does"
		);
	}

	@Test
	@DisplayName("a reference raised to partitioned after load is still walked in full")
	void referenceRaisedToPartitionedStillRegistersOwners() {
		// `brand` starts out merely filterable, so the trigger's sibling walk never visits its reduced index
		prepare(CatalogState.ALIVE, "INTERVAL_INPUT");
		upsertProducts(1, 5, 1);

		// raise it, exactly as a client would, and WITHOUT the reindex that would rebuild anything
		tx(session -> session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
			.openForWrite()
			.withReferenceToEntity(
				REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session));

		// now fire the cross-entity trigger: the group entity starts satisfying the expression
		tx(session -> session.getEntity(ENTITY_PARAMETER, PARAMETER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
			.upsertVia(session));

		// the facet must have reached the brand's reduced index even though the lookup never knew it -
		// this is the accelerator-not-authority contract, and its failure would be a wrong answer
		assertEquals(
			5, countInBrandWithFacetSelected(),
			"every product must survive selecting the facet inside the raised reference's index"
		);
	}

	/*
		ASSERTIONS
	 */

	/**
	 * Asserts, for every reference of the product collection and in every scope, that the reverse lookup
	 * agrees exactly with the reduced indexes it summarises.
	 *
	 * Ground truth is read from the collection's own indexes; the lookup's bookkeeping is never used to
	 * check itself.
	 */
	private void assertMembershipMatchesIndexes() {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		if (!(globalIndex instanceof final GlobalEntityIndex typedGlobalIndex)) {
			return;
		}
		for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
			final String referenceName = reference.getName();
			final Set<Integer> advertised = advertisedReducedIndexes(collection, referenceName);
			final ReducedIndexMembership membership =
				typedGlobalIndex.getReducedIndexMembership(referenceName);
			if (membership == null) {
				// Always legitimate, and deliberately so: with no lookup the trigger walks every index the
				// reference advertises, which is what it did before this structure existed. Absence costs
				// speed, never correctness - that is the accelerator-not-authority contract. What must never
				// happen is a lookup that EXISTS and is incomplete, which is what the rest of this asserts.
				continue;
			}
			final Set<Integer> covered = toSet(membership.getCoveredIndexPrimaryKeys());
			final Set<Integer> residual = toSet(membership.getResidualIndexPrimaryKeys());

			final Set<Integer> known = new TreeSet<>(covered);
			known.addAll(residual);
			assertEquals(
				new TreeSet<>(advertised), known,
				"reference `" + referenceName + "`: covered u residual must equal the advertised indexes"
			);
			assertTrue(
				disjoint(covered, residual),
				"reference `" + referenceName + "`: an index cannot be both covered and residual"
			);

			// every covered index's entries must name exactly its members, in both directions
			final Map<Integer, Set<Integer>> expected = new HashMap<>(covered.size());
			for (final Integer indexPk : covered) {
				final EntityIndex reducedIndex = collection.getIndexByPrimaryKeyIfExists(indexPk);
				assertTrue(
					reducedIndex != null,
					"reference `" + referenceName + "`: covered index " + indexPk + " does not exist"
				);
				final Bitmap members = reducedIndex.getAllPrimaryKeys();
				assertTrue(
					members.size() <= membership.getCoverageThreshold(),
					"reference `" + referenceName + "`: covered index " + indexPk + " holds " + members.size()
						+ " owners, above the coverage threshold of " + membership.getCoverageThreshold()
				);
				final OfInt it = members.iterator();
				while (it.hasNext()) {
					expected.computeIfAbsent(it.nextInt(), __ -> new TreeSet<>()).add(indexPk);
				}
			}
			final Set<Integer> coveredOwners = toSet(membership.getCoveredOwners());
			assertEquals(
				new TreeSet<>(expected.keySet()), new TreeSet<>(coveredOwners),
				"reference `" + referenceName + "`: coveredOwners must be exactly the owners of covered indexes"
			);
			for (final Map.Entry<Integer, Set<Integer>> entry : expected.entrySet()) {
				assertEquals(
					entry.getValue(), toSet(membership.getIndexPrimaryKeys(entry.getKey())),
					"reference `" + referenceName + "`: owner " + entry.getKey() +
						" must name exactly the covered indexes holding it"
				);
			}
		}
	}

	/**
	 * Asserts that the lookup is not merely *correct* but actually *engaged* for the given reference — it
	 * exists, and it accounts for every index the reference advertises.
	 *
	 * Kept separate from {@link #assertMembershipMatchesIndexes} on purpose. Absence of a lookup is correct
	 * behaviour and that method rightly tolerates it, which means a regression that stopped the lookup being
	 * built at all would leave every correctness assertion green while the optimization quietly did nothing.
	 * This is the assertion that would fail instead.
	 *
	 * @param referenceName the partitioned reference expected to be accelerated
	 */
	private void assertMembershipEngaged(@Nonnull String referenceName) {
		final ReducedIndexMembership membership = membershipOf(referenceName);
		assertTrue(
			membership != null,
			"reference `" + referenceName + "` must have a membership lookup - without one the trigger falls "
				+ "back to walking every reduced index, which is the cost this structure exists to remove"
		);
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final Set<Integer> advertised = advertisedReducedIndexes(collection, referenceName);
		final Set<Integer> known = new TreeSet<>(toSet(membership.getCoveredIndexPrimaryKeys()));
		known.addAll(toSet(membership.getResidualIndexPrimaryKeys()));
		assertEquals(
			new TreeSet<>(advertised), known,
			"reference `" + referenceName + "`: the lookup must account for every advertised index"
		);
	}

	/**
	 * Reads the reduced indexes a reference advertises, from the reference's own type indexes — the ground
	 * truth the lookup is checked against.
	 *
	 * @param collection    the collection to inspect
	 * @param referenceName reference whose advertisement is read
	 * @return advertised reduced-index primary keys
	 */
	@Nonnull
	private static Set<Integer> advertisedReducedIndexes(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName
	) {
		final Set<Integer> advertised = new HashSet<>(64);
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
				new EntityIndexKey(family, Scope.LIVE, referenceName)
			);
			if (typeIndex instanceof final ReferencedTypeEntityIndex typedTypeIndex) {
				typedTypeIndex.forEachReferenceIndexPrimaryKey(advertised::add);
			}
		}
		return advertised;
	}

	/**
	 * Materialises a bitmap as a set, so assertion failures print something a reader can act on.
	 *
	 * @param bitmap the bitmap to materialise
	 * @return its contents
	 */
	@Nonnull
	private static Set<Integer> toSet(@Nonnull Bitmap bitmap) {
		final Set<Integer> result = new TreeSet<>();
		final OfInt it = bitmap.iterator();
		while (it.hasNext()) {
			result.add(it.nextInt());
		}
		return result;
	}

	/**
	 * Returns `true` when the two sets share no element.
	 *
	 * @param left  first set
	 * @param right second set
	 * @return `true` when disjoint
	 */
	private static boolean disjoint(@Nonnull Set<Integer> left, @Nonnull Set<Integer> right) {
		for (final Integer value : left) {
			if (right.contains(value)) {
				return false;
			}
		}
		return true;
	}

	@Nonnull
	private Set<Integer> coveredOf(@Nonnull String referenceName) {
		final ReducedIndexMembership membership = membershipOf(referenceName);
		return membership == null ? Set.of() : toSet(membership.getCoveredIndexPrimaryKeys());
	}

	@Nonnull
	private Set<Integer> residualOf(@Nonnull String referenceName) {
		final ReducedIndexMembership membership = membershipOf(referenceName);
		return membership == null ? Set.of() : toSet(membership.getResidualIndexPrimaryKeys());
	}

	private ReducedIndexMembership membershipOf(@Nonnull String referenceName) {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		return globalIndex instanceof final GlobalEntityIndex typed
			? typed.getReducedIndexMembership(referenceName) : null;
	}

	/**
	 * Blocks until the reopened catalog has finished loading. `new Evita(...)` returns while the catalog is
	 * still `BEING_ACTIVATED` on the service pool, and the reverse lookup is rebuilt at the very end of that
	 * load — so reading it any earlier would be a race, not a measurement.
	 */
	private void awaitCatalogLoaded() {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(TEST_CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/*
		FIXTURE
	 */

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	/**
	 * Creates the schema and fixture entities and brings the catalog to the requested state.
	 *
	 * @param state      target catalog state
	 * @param widgetType initial value of the group entity's attribute, which decides whether the conditional
	 *                   facet is on
	 */
	private void prepare(@Nonnull CatalogState state, @Nonnull String widgetType) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER_VALUE).updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER)
					.withAttribute(
						ATTR_INPUT_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					// starts merely filterable so it can be raised later, without a reindex
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFiltering()
					)
					.withReferenceToEntity(
						REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.facetedPartially(
								ExpressionFactory.parse(
									"$reference.groupEntity?.attributes['" + ATTR_INPUT_WIDGET_TYPE
										+ "'] == 'CHECKBOX'"
								)
							)
					)
					.updateVia(session);

				for (int pk = 1; pk <= OVER_THRESHOLD; pk++) {
					session.createNewEntity(ENTITY_CATEGORY, pk).upsertVia(session);
				}
				session.createNewEntity(ENTITY_BRAND, BRAND_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PARAMETER_VALUE, PARAM_VALUE_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PARAMETER, PARAMETER_PK)
					.setAttribute(ATTR_INPUT_WIDGET_TYPE, widgetType)
					.upsertVia(session);

				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Upserts a run of products, each filed under one shared category plus its own, so both the many-owner
	 * and the single-owner shapes exist at once.
	 *
	 * @param fromPk          first product primary key, inclusive
	 * @param toPk            last product primary key, inclusive
	 * @param sharedCategory  category every product joins
	 */
	private void upsertProducts(int fromPk, int toPk, int sharedCategory) {
		for (int pk = fromPk; pk <= toPk; pk++) {
			final int productPk = pk;
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setReference(REF_CATEGORIES, sharedCategory)
				.setReference(REF_BRAND, BRAND_PK)
				.setReference(
					REF_PARAMETER_VALUES, PARAM_VALUE_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
				)
				.upsertVia(session));
		}
	}

	private void tx(@Nonnull Consumer<EvitaSessionContract> work) {
		this.evita.updateCatalog(TEST_CATALOG, work);
	}

	/**
	 * Counts products of {@link #BRAND_PK} surviving a selection of the conditional facet — the query that is
	 * evaluated against the brand's reduced index, and therefore the one that exposes a facet the trigger
	 * failed to write there.
	 *
	 * @return number of matching products
	 */
	private int countInBrandWithFacetSelected() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						referenceHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND_PK)),
						userFilter(facetHaving(REF_PARAMETER_VALUES, entityPrimaryKeyInSet(PARAM_VALUE_PK)))
					),
					require(page(1, 1))
				),
				EntityReference.class
			).getTotalRecordCount()
		);
	}

	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}
}
