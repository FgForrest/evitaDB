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
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * It runs over **every scope**, and it refuses to pass vacuously: a scope whose global index is absent must
 * also advertise no reduced indexes, or the assertion fails rather than returning silently. Both halves matter,
 * because the `ARCHIVED` half of the rebuild loop and of the maintenance hooks is otherwise unasserted end to
 * end.
 *
 * It runs after each of the boundary classes in turn: bulk load, a later join, a departure, an entity removed
 * outright, a threshold crossing in each direction, an entity moved between scopes, a schema change that ends
 * maintenance, a rolled-back write, and a catalog reloaded from disk — the last because the lookup is derived
 * state that is never persisted, and a reload that failed to rebuild it would leave a collection permanently
 * unaccelerated (or, worse, half-accelerated) with nothing else to show for it.
 *
 * The other half of the contract is the one that would be a wrong answer rather than a slow one: the lookup is
 * an **accelerator, never an authority**, so a reference whose indexes it does not know must still be walked in
 * full. That is exactly what a `ReferenceIndexType` raised on an already-populated collection produces — the
 * engine does not rebuild indexes for a schema change (issue #409) — and the facet must still reach every index
 * it owes.
 *
 * Several tests attack it, each from a state in which the lookup is absent, incomplete or simply false.
 * {@link #reloadThenRaiseReferenceToPartitionedStillReachesEveryIndex} and
 * {@link #referenceLoweredAndRaisedAgainStillReachesEveryIndex} drive the reference across the index level the
 * maintenance hooks are gated on; {@link #conditionalFacetDroppedAndReDeclaredStillReachesEveryIndex} drives
 * the *other* gate, the collection's last conditional facet, across the same boundary; and
 * {@link #siblingWalkSurvivesAStaleCoveredEntry} feeds the lookup entries that are simply false. In all of them
 * the facet must still reach every partition it owes.
 *
 * The gates are pure functions of the schema, so a schema change is the only event that can end maintenance —
 * which is why the discard hangs off `EntityCollection#exchangeSchema`. That placement is itself asserted
 * rather than argued: {@link #reflectedReferenceLoweredFromTheSourceCollectionDiscardsItsLookup} lowers a
 * reference in the collection a **reflected** one reflects, an adoption path that never passes through
 * `updateSchema`, and requires the reflected reference's lookup to be gone.
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
	private static final String ENTITY_TAG = "tag";

	private static final String REF_CATEGORIES = "categories";
	private static final String REF_BRAND = "brand";
	private static final String REF_PARAMETER_VALUES = "parameterValues";
	private static final String REF_TAGS = "tags";
	/** Reference on the CATEGORY collection pointing back at products - the source of the reflected one below. */
	private static final String REF_PRODUCTS = "products";
	/** Reflected counterpart on the PRODUCT collection; it inherits its index type from {@link #REF_PRODUCTS}. */
	private static final String REF_REFLECTED_CATEGORIES = "categoriesReflected";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_TAG_NOTE = "tagNote";

	private static final int PARAMETER_PK = 100;
	private static final int PARAM_VALUE_PK = 10;
	private static final int BRAND_PK = 7;
	private static final int SECOND_BRAND_PK = 8;
	private static final int TAG_PK = 55;

	/**
	 * Primary key of the product every rollback test writes and expects to disappear again.
	 */
	private static final int REVERTED_PRODUCT_PK = 900;

	/**
	 * Category the reverted product joins, so the rollback has a brand-new reduced index to undo as well as an
	 * entry in an existing one.
	 */
	private static final int REVERTED_CATEGORY_PK = 24;

	/**
	 * Comfortably more than `ReducedIndexMembership.DEFAULT_COVERAGE_THRESHOLD`, so a category can be pushed
	 * over the coverage boundary and pulled back under it within one test.
	 */
	private static final int OVER_THRESHOLD = 25;

	/**
	 * A storage primary key no index of the test catalog can ever hold — index primary keys are handed out
	 * from zero upwards — so a lookup entry naming it is guaranteed to be unresolvable.
	 */
	private static final int UNRESOLVABLE_INDEX_PK = Integer.MAX_VALUE - 1;

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
		assertFalse(
			residualOf(REF_CATEGORIES).isEmpty(),
			"a category of " + OVER_THRESHOLD + " owners must have been promoted out of coverage"
		);
		// ... and shrinks back far enough to be demoted into coverage again
		for (int pk = 2; pk <= OVER_THRESHOLD; pk++) {
			final int productPk = pk;
			tx(session -> session.deleteEntity(ENTITY_PRODUCT, productPk));
		}
		assertMembershipMatchesIndexes();
		// the demotion has to be asserted directly: a permanently-residual index satisfies the completeness
		// assertion perfectly well, so without this a regression that dropped the demotion branch would leave
		// this test green while its own name became false
		assertTrue(
			residualOf(REF_CATEGORIES).isEmpty(),
			"the shared category now holds a single owner and must have been demoted back into coverage"
		);
		assertFalse(
			coveredOf(REF_CATEGORIES).isEmpty(),
			"the shared category's reduced index must be covered after the shrink"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("archiving an owner moves its membership between scopes, and restoring moves it back")
	void archivingAnOwnerMovesItsMembershipBetweenScopes(CatalogState state) {
		prepare(state, "CHECKBOX");
		upsertProducts(1, 5, 1);
		assertMembershipMatchesIndexes();
		assertTrue(
			coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(3),
			"the owner must be covered in LIVE before it is archived, or the move below proves nothing"
		);

		tx(session -> session.archiveEntity(ENTITY_PRODUCT, 3));

		assertMembershipMatchesIndexes();
		assertFalse(
			coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(3),
			"an archived owner left every LIVE reduced index, so the LIVE lookup must have forgotten it - a "
				+ "retained entry names a partition the owner is no longer in, which is a wrong facet"
		);
		assertTrue(
			coveredOwnersOf(REF_CATEGORIES, Scope.ARCHIVED).contains(3),
			"the ARCHIVED lookup must know the owner it just gained - without it the ARCHIVED trigger applies "
				+ "no facet to the owner's partition at all"
		);

		tx(session -> session.restoreEntity(ENTITY_PRODUCT, 3));

		assertMembershipMatchesIndexes();
		assertTrue(
			coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(3),
			"a restored owner must be known to the LIVE lookup again"
		);
		assertFalse(
			coveredOwnersOf(REF_CATEGORIES, Scope.ARCHIVED).contains(3),
			"the ARCHIVED lookup must have forgotten the restored owner"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a collection declaring no conditional facet grows no lookup on write either")
	void collectionWithoutConditionalFacetGrowsNoLookupOnWrite(CatalogState state) {
		// The load-time build skips such a collection outright, and maintenance has to agree: the cross-entity
		// trigger can never fire here, so every entry written on the write path would be maintained for a reader
		// that never comes - and `categories` IS partitioned, so without the gate the writes below would build a
		// full lookup for it.
		prepare(state, "CHECKBOX", EnumSet.noneOf(Scope.class));
		upsertProducts(1, 5, 1);

		assertMembershipMatchesIndexes();
		final EntityCollection collection = (EntityCollection) getProductCollection();
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
				assertNull(
					membershipOf(reference.getName(), scope),
					"scope " + scope + ", reference `" + reference.getName() + "`: no reference of this "
						+ "collection declares a conditional facet, so the trigger never fires and a lookup here "
						+ "is maintained on every write for nobody"
				);
			}
		}
	}

	@Test
	@DisplayName("a reflected reference lowered by the OTHER collection's schema change loses its lookup")
	void reflectedReferenceLoweredFromTheSourceCollectionDiscardsItsLookup() {
		// The placement of the discard is what this test exists for. A reflected reference inherits its index type
		// from the reference it reflects, which lives in ANOTHER collection - and when that one changes, this
		// collection adopts the new schema through `notifyAboutExternalReferenceUpdate` -> `exchangeSchema`,
		// never through its own `updateSchema`. A discard hooked on `updateSchema` would therefore never fire for
		// a reflected reference, and the lookup would be trusted again the moment the source raises it back.
		prepareReflected();
		assertNotNull(
			membershipOf(REF_REFLECTED_CATEGORIES, Scope.LIVE),
			"the reflected reference inherits FOR_FILTERING_AND_PARTITIONING, so its lookup must have been built"
		);
		assertMembershipMatchesIndexes();

		// the schema change happens on the CATEGORY collection, not on this one
		setCategoryProductsIndexing(false);

		assertNull(
			membershipOf(REF_REFLECTED_CATEGORIES, Scope.LIVE),
			"the reflected reference is no longer partitioned, so nothing maintains its lookup and it must have "
				+ "been discarded - a lookup kept here is trusted again the moment the source reference is raised"
		);
		assertMembershipMatchesIndexes();

		// a partition created while nothing was watching the reflected reference ...
		upsertCategoryWithProducts(2, 1, 5);
		// ... and the source raises it again
		setCategoryProductsIndexing(true);
		fireCrossEntityTrigger();

		assertMembershipMatchesIndexes();
		assertEquals(
			5, countInPartitionWithFacetSelected(REF_REFLECTED_CATEGORIES, 2),
			"the partition that appeared while the reflected reference was merely filterable must receive the "
				+ "facet too - the lookup could not have recorded it, so it must not have been trusted"
		);
	}

	@Test
	@DisplayName("dropping the collection's last conditional facet and re-declaring it still reaches every index")
	void conditionalFacetDroppedAndReDeclaredStillReachesEveryIndex() {
		// The third instance of one defect class: maintenance is gated on the collection declaring a conditional
		// facet, so dropping the last one stops it while the reduced indexes go on changing - and re-declaring it
		// would put the trigger back on a lookup frozen at the moment the facet went away. The first two instances
		// were a reference raised after a reload and a reference lowered and raised again.
		prepare(CatalogState.ALIVE, "INTERVAL_INPUT");
		upsertProducts(1, 5, 1);
		assertNotNull(membershipOf(REF_CATEGORIES, Scope.LIVE), "the lookup must exist before the facet is dropped");

		setConditionalFacet(false);
		// a category the lookup has never seen, created while nothing is watching it
		upsertProducts(6, 10, 2);
		setConditionalFacet(true);

		fireCrossEntityTrigger();

		assertEquals(
			5, countInCategoryWithFacetSelected(1),
			"the category the lookup knew must still receive the facet"
		);
		assertEquals(
			5, countInCategoryWithFacetSelected(2),
			"the category whose index appeared while the collection had no conditional facet must receive the "
				+ "facet too - the lookup could not have recorded it, so it must not have been trusted"
		);
	}

	@Test
	@DisplayName("a COMMITTED write that discards a lookup leaves the catalog usable")
	void committedLoweringDiscardsTheLookupWithoutBreakingTheCommit() {
		// The discard is the only production path that removes a whole slice from the transactional map holding
		// them, and a removal whose nested diff layers are not swept fails the commit AFTER the version has
		// reached disk - which suspends the catalog and leaves a version on disk nobody can trust. The rolled-back
		// twin of this test cannot see that: a rejected transaction throws its layers away wholesale. This one
		// commits.
		prepare(CatalogState.ALIVE, "CHECKBOX");
		upsertProducts(1, 5, 1);
		assertFalse(coveredOf(REF_CATEGORIES).isEmpty(), "the lookup must hold coverage before it is discarded");

		setCategoriesIndexing(false);
		// the write that fires the discard, and commits
		upsertProducts(6, 6, 2);

		assertNull(
			membershipOf(REF_CATEGORIES, Scope.LIVE),
			"the lookup of a reference that is no longer partitioned must have been discarded"
		);
		assertMembershipMatchesIndexes();
		// a suspended catalog refuses the next write, and a failed commit would have suspended it
		upsertProducts(7, 7, 2);
		assertEquals(
			2, countInCategoryWithFacetSelected(2),
			"the catalog must still answer queries over the partition written after the discard"
		);
	}

	@Test
	@DisplayName("a lookup created and discarded inside ONE transaction leaves the catalog usable")
	void lookupCreatedAndDiscardedInOneTransactionLeavesTheCatalogUsable() {
		// The created-then-removed shape: the slice is created by the first write of this transaction, so its key
		// lives only in the transaction's diff layer and never in the committed map - the case a commit-time
		// sweep that only walks the committed map cannot see. Driven here through the public API alone, by
		// lowering the reference in the same transaction that created the slice.
		prepare(CatalogState.ALIVE, "CHECKBOX");
		assertNull(
			membershipOf(REF_CATEGORIES, Scope.LIVE),
			"no product has been written yet, so no lookup may exist - or this test proves nothing"
		);

		tx(session -> {
			// (1) creates the lookup for `categories` and records this owner in it
			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(REF_CATEGORIES, 1)
				.setReference(REF_BRAND, BRAND_PK)
				.setReference(
					REF_PARAMETER_VALUES, PARAM_VALUE_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
				)
				.upsertVia(session);
			// (2) lowers the reference, so the next write cannot keep the lookup current any more
			session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
				.openForWrite()
				.withReferenceToEntity(
					REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFiltering()
				)
				.updateVia(session);
			// (3) discards the lookup created in step (1), in the same transaction
			session.createNewEntity(ENTITY_PRODUCT, 2)
				.setReference(REF_CATEGORIES, 2)
				.setReference(REF_BRAND, BRAND_PK)
				.setReference(
					REF_PARAMETER_VALUES, PARAM_VALUE_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
				)
				.upsertVia(session);
		});

		assertNull(
			membershipOf(REF_CATEGORIES, Scope.LIVE),
			"the lookup created and discarded inside the transaction must not survive it"
		);
		assertMembershipMatchesIndexes();
		// the commit above must have completed: a failed one suspends the catalog and the next write is refused
		upsertProducts(3, 3, 2);
		assertMembershipMatchesIndexes();
	}

	@Test
	@DisplayName("a reverted transaction that lowered a reference leaves its lookup intact")
	void revertedLoweringLeavesTheLookupIntact() {
		// Lowering a reference out of partitioning DISCARDS its lookup - the only production path that removes a
		// whole slice from the map holding them. Here the lowering happens inside a transaction that is then
		// refused, after every index has already been mutated, so the removal has to rewind with everything else.
		// A removal that failed to rewind would leave the reference permanently unaccelerated - and, because a
		// stranded diff layer fails the NEXT commit rather than this one, would surface far from its cause.
		//
		// `ALIVE` only: in `WARMING_UP` a schema change is applied immediately and belongs to no rewind unit, so
		// there would be nothing for the refused write to take back.
		prepare(CatalogState.ALIVE, "CHECKBOX");
		upsertProducts(1, 5, 1);
		assertMembershipMatchesIndexes();
		final Set<Integer> coveredBefore = coveredOf(REF_CATEGORIES);
		assertFalse(coveredBefore.isEmpty(), "the lookup must hold coverage before the write, or nothing is lost");

		// the `tags` reference is missing its mandatory attribute, and consistency is verified only after the
		// schema change - and hence the discard - has already run
		assertRefusedForMissingMandatoryAttribute(
			() -> tx(session -> {
				session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
					.openForWrite()
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_PRODUCT, REVERTED_PRODUCT_PK)
					.setReference(REF_TAGS, TAG_PK)
					.upsertVia(session);
			})
		);

		assertNotNull(
			membershipOf(REF_CATEGORIES, Scope.LIVE),
			"the discard was part of a transaction that never happened, so the lookup must still be there"
		);
		assertEquals(
			coveredBefore, coveredOf(REF_CATEGORIES),
			"the rewound lookup must hold exactly the coverage it held before the refused transaction"
		);
		assertMembershipMatchesIndexes();

		// A rewind that left a transactional layer behind fails the NEXT commit, not this one, and would be
		// invisible here otherwise - so a further write is required to succeed.
		upsertProducts(6, 7, 1);
		assertMembershipMatchesIndexes();
		assertTrue(
			coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(6),
			"the lookup must still be maintained after the refused transaction"
		);
	}

	@Test
	@DisplayName("a scope declaring no conditional facet grows no lookup when the catalog is loaded")
	void archivedScopeWithoutConditionalFacetGrowsNoSlice() {
		// the conditional facet is declared in LIVE only, so the ARCHIVED trigger never fires and the ARCHIVED
		// half of the load-time build must do nothing at all
		prepare(CatalogState.ALIVE, "CHECKBOX", EnumSet.of(Scope.LIVE));
		upsertProducts(1, 5, 1);
		tx(session -> session.archiveEntity(ENTITY_PRODUCT, 3));

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		awaitCatalogLoaded();

		assertMembershipMatchesIndexes();
		assertNotNull(
			membershipOf(REF_CATEGORIES, Scope.LIVE),
			"the LIVE scope declares the conditional facet, so its lookup must have been rebuilt"
		);
		final EntityCollection collection = (EntityCollection) getProductCollection();
		for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
			assertNull(
				membershipOf(reference.getName(), Scope.ARCHIVED),
				"reference `" + reference.getName() + "`: the ARCHIVED scope declares no conditional facet, so "
					+ "building a lookup for it costs memory the trigger will never read"
			);
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("a reverted write leaves the lookup matching the indexes")
	void revertedWriteLeavesTheLookupMatchingTheIndexes(CatalogState state) {
		// The two states rewind through two entirely different mechanisms and only the structures each one knows
		// about come back: `ALIVE` restores the transactional diff layers through the savepoint maintainer, while
		// `WARMING_UP` replays the inverses the structures journalled into the `WarmUpSavepoint` - warm-up writes
		// went in place, so there is no layer to throw away. A structure hooked into one and not the other looks
		// perfectly correct until the other path runs.
		prepare(state, "CHECKBOX");
		upsertProducts(1, 5, 1);
		assertMembershipMatchesIndexes();

		// the failing entity joins a brand-new reduced index AND an existing one, so the rewind has both an
		// index registration and a plain membership entry to undo. The `tags` reference is missing its mandatory
		// attribute, and consistency is verified only after every index has already been mutated.
		assertRefusedForMissingMandatoryAttribute(
			() -> tx(session -> session.createNewEntity(ENTITY_PRODUCT, REVERTED_PRODUCT_PK)
				.setReference(REF_CATEGORIES, 1)
				.setReference(REF_CATEGORIES, REVERTED_CATEGORY_PK)
				.setReference(REF_TAGS, TAG_PK)
				.upsertVia(session))
		);

		assertMembershipMatchesIndexes();
		assertFalse(
			coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(REVERTED_PRODUCT_PK),
			"the reverted owner must be gone from the lookup - it is gone from the indexes, so an entry naming "
				+ "it would send the trigger at a partition that no longer holds it"
		);

		// a later write must still succeed: a rewind that left a diff layer behind fails the NEXT commit, not
		// this one, and would otherwise be invisible here
		upsertProducts(6, 7, 1);
		assertMembershipMatchesIndexes();
		assertTrue(coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(6));
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
		assertFalse(
			coveredOf(REF_CATEGORIES).isEmpty(),
			"a reloaded collection must regain COVERAGE, not merely a residual list - the load-time build "
				+ "resolves each index and decides coverage, unlike the seeding maintenance does"
		);
	}

	@Test
	@DisplayName("every reference the lookup is keyed by is still declared by the schema")
	void everyReferenceNameInTheLookupIsInTheSchema() {
		prepare(CatalogState.ALIVE, "CHECKBOX");
		upsertProducts(1, 5, 1);

		final EntityCollection collection = (EntityCollection) getProductCollection();
		final Set<String> declared = collection.getSchema().getReferences().keySet();
		for (final Scope scope : Scope.values()) {
			final GlobalEntityIndex globalIndex = globalIndexOf(scope);
			if (globalIndex == null) {
				continue;
			}
			for (final String referenceName : globalIndex.getReducedIndexMembershipReferenceNames()) {
				assertTrue(
					declared.contains(referenceName),
					"scope " + scope + ": the lookup is keyed by `" + referenceName + "`, which the schema no "
						+ "longer declares - such a slice is never read and never freed"
				);
			}
		}
		assertFalse(
			globalIndexOf(Scope.LIVE).getReducedIndexMembershipReferenceNames().isEmpty(),
			"the LIVE global index must hold at least one lookup here, or the loop above asserts nothing"
		);
	}

	@Test
	@DisplayName("a reference raised to partitioned after a RELOAD still reaches every index")
	void reloadThenRaiseReferenceToPartitionedStillReachesEveryIndex() {
		// Same schema change as the test above, but with a catalog reload in between. That is the difference
		// that matters: were the load-time build to register a non-partitioned reference's reduced indexes, a
		// slice would EXIST, and the trigger would take the accelerated branch over it instead of walking -
		// while the maintenance hooks, gated on partitioning, recorded nothing into it. Every index created
		// after the load would then be in neither the covered nor the residual set, and silently skipped.
		prepare(CatalogState.ALIVE, "INTERVAL_INPUT");
		upsertProducts(1, 5, 1);

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		awaitCatalogLoaded();

		// a reduced index created AFTER the load, while `brand` is still merely filterable
		tx(session -> session.createNewEntity(ENTITY_BRAND, SECOND_BRAND_PK).upsertVia(session));
		upsertProducts(6, 10, 1, SECOND_BRAND_PK);

		raiseBrandToPartitioned();
		fireCrossEntityTrigger();

		assertEquals(
			5, countInBrandWithFacetSelected(BRAND_PK),
			"the brand the load saw must still receive the facet"
		);
		assertEquals(
			5, countInBrandWithFacetSelected(SECOND_BRAND_PK),
			"the brand created after the load must receive the facet too - a reference the lookup does not "
				+ "cover is walked in full, and a raise without a reindex is exactly that case"
		);
	}

	@Test
	@DisplayName("a reference lowered and raised again still reaches the indexes created in between")
	void referenceLoweredAndRaisedAgainStillReachesEveryIndex() {
		// The lookup is maintained only while the reference is indexed for partitioning. Lowering it stops the
		// hooks; the reduced indexes go on changing regardless; raising it back puts the trigger on the
		// accelerated branch again. Anything the lookup missed in between is an index the trigger would never
		// visit - a wrong facet, not a slow one - so the lookup has to be discarded the moment it stops being
		// able to keep up.
		prepare(CatalogState.ALIVE, "INTERVAL_INPUT");
		upsertProducts(1, 5, 1);
		assertTrue(
			coveredOwnersOf(REF_CATEGORIES, Scope.LIVE).contains(1),
			"the owners must be covered before the reference is lowered, or the lookup has nothing to lose"
		);

		setCategoriesIndexing(false);
		// a brand-new reduced index for a category the lookup has never seen, created while nothing is watching
		upsertProducts(6, 10, 2);
		setCategoriesIndexing(true);

		fireCrossEntityTrigger();

		assertEquals(
			5, countInCategoryWithFacetSelected(1),
			"the category the lookup knew must still receive the facet"
		);
		assertEquals(
			5, countInCategoryWithFacetSelected(2),
			"the category whose index appeared while the reference was merely filterable must receive the "
				+ "facet too - the lookup could not have recorded it, so it must not have been trusted"
		);
	}

	@Test
	@DisplayName("the sibling walk survives a covered entry naming a reduced index that no longer exists")
	void siblingWalkSurvivesAStaleCoveredEntry() {
		// The map's own contract is that nothing it records is an answer: it selects which reduced indexes are
		// worth probing, and each index is then asked itself who is in it. This drives it into the two states
		// that contract exists to survive - an entry naming an index the collection does not hold, and an owner
		// recorded against an index it is not a member of - and asserts the trigger neither fails nor writes a
		// facet anywhere it does not belong. The entries are injected rather than provoked because every
		// production route into them is closed; the guard is what keeps the next closed route from mattering.
		prepare(CatalogState.ALIVE, "INTERVAL_INPUT");
		upsertProducts(1, 5, 1);
		upsertProducts(6, 10, 2);

		final ReducedIndexMembership membership = membershipOf(REF_CATEGORIES, Scope.LIVE);
		assertNotNull(membership, "the lookup must exist, or there is nothing to make stale");
		// an index that does not exist, recorded as holding owners that do exist
		membership.registerIndex(UNRESOLVABLE_INDEX_PK, new BaseBitmap(1, 2, 3));
		// ... and owners recorded against the reduced index of a category they were never filed under
		final int categoryTwoIndexPk = reducedIndexPkOf(REF_CATEGORIES, 2);
		membership.ownerAdded(categoryTwoIndexPk, 1, new BaseBitmap(1, 6, 7, 8, 9, 10));

		fireCrossEntityTrigger();

		assertEquals(
			5, countInCategoryWithFacetSelected(1),
			"the products of the first category must survive selecting the facet"
		);
		assertEquals(
			5, countInCategoryWithFacetSelected(2),
			"the second category must hold exactly its own products - an owner the lookup wrongly named must "
				+ "not gain a facet in a partition it is not in"
		);
	}

	/*
		ASSERTIONS
	 */

	/**
	 * Asserts that the reverse lookup agrees exactly with the reduced indexes it summarises, in **every** scope.
	 *
	 * Ground truth is read from the collection's own indexes; the lookup's bookkeeping is never used to
	 * check itself.
	 */
	private void assertMembershipMatchesIndexes() {
		for (final Scope scope : Scope.values()) {
			assertMembershipMatchesIndexes(scope);
		}
	}

	/**
	 * Asserts, for every reference of the product collection in one scope, that the reverse lookup agrees
	 * exactly with the reduced indexes it summarises.
	 *
	 * @param scope the scope whose indexes and lookups are compared
	 */
	private void assertMembershipMatchesIndexes(@Nonnull Scope scope) {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final GlobalEntityIndex typedGlobalIndex = globalIndexOf(scope);
		if (typedGlobalIndex == null) {
			// A scope with no global index holds no entities, and an empty index is swept away rather than kept.
			// That is legitimate - but only while the scope also advertises no reduced index, so it is asserted
			// rather than assumed. Returning silently here is how the whole class could pass vacuously.
			for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
				assertTrue(
					advertisedReducedIndexes(collection, reference.getName(), scope).isEmpty(),
					"scope " + scope + ", reference `" + reference.getName() + "`: reduced indexes are "
						+ "advertised but there is no global index to hold their lookup"
				);
			}
			return;
		}
		for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
			final String referenceName = reference.getName();
			final Set<Integer> advertised = advertisedReducedIndexes(collection, referenceName, scope);
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
				"scope " + scope + ", reference `" + referenceName
					+ "`: covered u residual must equal the advertised indexes"
			);
			assertTrue(
				Collections.disjoint(covered, residual),
				"scope " + scope + ", reference `" + referenceName
					+ "`: an index cannot be both covered and residual"
			);

			// every covered index's entries must name exactly its members, in both directions
			final Map<Integer, Set<Integer>> expected = CollectionUtils.createHashMap(covered.size());
			for (final Integer indexPk : covered) {
				final EntityIndex reducedIndex = collection.getIndexByPrimaryKeyIfExists(indexPk);
				assertNotNull(
					reducedIndex,
					"scope " + scope + ", reference `" + referenceName + "`: covered index " + indexPk
						+ " does not exist"
				);
				final Bitmap members = reducedIndex.getAllPrimaryKeys();
				assertTrue(
					members.size() <= membership.getCoverageThreshold(),
					"scope " + scope + ", reference `" + referenceName + "`: covered index " + indexPk
						+ " holds " + members.size() + " owners, above the coverage threshold of "
						+ membership.getCoverageThreshold()
				);
				final OfInt it = members.iterator();
				while (it.hasNext()) {
					expected.computeIfAbsent(it.nextInt(), __ -> new TreeSet<>()).add(indexPk);
				}
			}
			final Set<Integer> coveredOwners = toSet(membership.getCoveredOwners());
			assertEquals(
				new TreeSet<>(expected.keySet()), new TreeSet<>(coveredOwners),
				"scope " + scope + ", reference `" + referenceName
					+ "`: coveredOwners must be exactly the owners of covered indexes"
			);
			for (final Map.Entry<Integer, Set<Integer>> entry : expected.entrySet()) {
				assertEquals(
					entry.getValue(), toSet(membership.getIndexPrimaryKeys(entry.getKey())),
					"scope " + scope + ", reference `" + referenceName + "`: owner " + entry.getKey() +
						" must name exactly the covered indexes holding it"
				);
			}
		}
	}

	/**
	 * Asserts that the lookup is not merely *correct* but actually *engaged* for the given reference in the LIVE
	 * scope — it exists, and it accounts for every index the reference advertises.
	 *
	 * Kept separate from {@link #assertMembershipMatchesIndexes} on purpose. Absence of a lookup is correct
	 * behaviour and that method rightly tolerates it, which means a regression that stopped the lookup being
	 * built at all would leave every correctness assertion green while the optimization quietly did nothing.
	 * This is the assertion that would fail instead.
	 *
	 * @param referenceName the partitioned reference expected to be accelerated
	 */
	private void assertMembershipEngaged(@Nonnull String referenceName) {
		final ReducedIndexMembership membership = membershipOf(referenceName, Scope.LIVE);
		assertNotNull(
			membership,
			"reference `" + referenceName + "` must have a membership lookup - without one the trigger falls "
				+ "back to walking every reduced index, which is the cost this structure exists to remove"
		);
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final Set<Integer> advertised = advertisedReducedIndexes(collection, referenceName, Scope.LIVE);
		final Set<Integer> known = new TreeSet<>(toSet(membership.getCoveredIndexPrimaryKeys()));
		known.addAll(toSet(membership.getResidualIndexPrimaryKeys()));
		assertEquals(
			new TreeSet<>(advertised), known,
			"reference `" + referenceName + "`: the lookup must account for every advertised index"
		);
	}

	/**
	 * Asserts the given work is refused because a mandatory reference attribute is missing, whichever wrapper the
	 * write path puts around the refusal — the transactional path wraps where the warm-up path does not.
	 *
	 * @param work the write expected to be refused
	 */
	private static void assertRefusedForMissingMandatoryAttribute(@Nonnull Executable work) {
		final Throwable thrown = assertThrows(
			RuntimeException.class, work,
			"the upsert omits a mandatory reference attribute and must be refused"
		);
		assertNotNull(
			ExceptionUtils.findInCauseChain(thrown, MandatoryAttributesNotProvidedException.class),
			"the write must fail on the missing mandatory reference attribute, but failed with: "
				+ messageChainOf(thrown)
		);
	}

	/**
	 * Joins the messages of a throwable and its causes, so an assertion can look for a phrase wherever in the
	 * chain the write path happened to put it.
	 *
	 * @param throwable the throwable to flatten
	 * @return the concatenated messages
	 */
	@Nonnull
	private static String messageChainOf(@Nonnull Throwable throwable) {
		final StringBuilder result = new StringBuilder(256);
		Throwable current = throwable;
		while (current != null) {
			result.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage())
				.append(" | ");
			current = current.getCause();
		}
		return result.toString();
	}

	/**
	 * Reads the reduced indexes a reference advertises, from the reference's own type indexes — the ground
	 * truth the lookup is checked against.
	 *
	 * @param collection    the collection to inspect
	 * @param referenceName reference whose advertisement is read
	 * @param scope         the scope whose type indexes are read
	 * @return advertised reduced-index primary keys
	 */
	@Nonnull
	private static Set<Integer> advertisedReducedIndexes(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final Set<Integer> advertised = CollectionUtils.createHashSet(64);
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
				new EntityIndexKey(family, scope, referenceName)
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
	 * Returns the covered reduced-index primary keys of a reference's LIVE lookup.
	 *
	 * @param referenceName reference to read
	 * @return covered index primary keys, empty when there is no lookup
	 */
	@Nonnull
	private Set<Integer> coveredOf(@Nonnull String referenceName) {
		final ReducedIndexMembership membership = membershipOf(referenceName, Scope.LIVE);
		return membership == null ? Set.of() : toSet(membership.getCoveredIndexPrimaryKeys());
	}

	/**
	 * Returns the residual reduced-index primary keys of a reference's LIVE lookup.
	 *
	 * @param referenceName reference to read
	 * @return residual index primary keys, empty when there is no lookup
	 */
	@Nonnull
	private Set<Integer> residualOf(@Nonnull String referenceName) {
		final ReducedIndexMembership membership = membershipOf(referenceName, Scope.LIVE);
		return membership == null ? Set.of() : toSet(membership.getResidualIndexPrimaryKeys());
	}

	/**
	 * Returns the owners a reference's lookup covers in the given scope.
	 *
	 * @param referenceName reference to read
	 * @param scope         the scope whose lookup is read
	 * @return covered owner primary keys, empty when there is no lookup
	 */
	@Nonnull
	private Set<Integer> coveredOwnersOf(@Nonnull String referenceName, @Nonnull Scope scope) {
		final ReducedIndexMembership membership = membershipOf(referenceName, scope);
		return membership == null ? Set.of() : toSet(membership.getCoveredOwners());
	}

	/**
	 * Returns a reference's reverse lookup in the given scope, or `null` when it has none.
	 *
	 * @param referenceName reference to read
	 * @param scope         the scope whose lookup is read
	 * @return the lookup, or `null`
	 */
	@Nullable
	private ReducedIndexMembership membershipOf(@Nonnull String referenceName, @Nonnull Scope scope) {
		final GlobalEntityIndex globalIndex = globalIndexOf(scope);
		return globalIndex == null ? null : globalIndex.getReducedIndexMembership(referenceName);
	}

	/**
	 * Returns the product collection's global index in the given scope, failing when the key resolves to
	 * something that is not one — an index registered under a `GLOBAL` key is a {@link GlobalEntityIndex} by
	 * construction, so anything else is a programming error and must not be read as "absent".
	 *
	 * @param scope the scope whose global index is read
	 * @return the global index, or `null` when the scope holds none
	 */
	@Nullable
	private GlobalEntityIndex globalIndexOf(@Nonnull Scope scope) {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		if (globalIndex == null) {
			return null;
		}
		assertTrue(
			globalIndex instanceof GlobalEntityIndex,
			"scope " + scope + ": the GLOBAL index key resolved to " + globalIndex.getClass().getName()
		);
		return (GlobalEntityIndex) globalIndex;
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
	 * Creates the schema and fixture entities in both scopes and brings the catalog to the requested state.
	 *
	 * @param state      target catalog state
	 * @param widgetType initial value of the group entity's attribute, which decides whether the conditional
	 *                   facet is on
	 */
	private void prepare(@Nonnull CatalogState state, @Nonnull String widgetType) {
		prepare(state, widgetType, EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
	}

	/**
	 * Creates the schema and fixture entities and brings the catalog to the requested state.
	 *
	 * @param state                   target catalog state
	 * @param widgetType              initial value of the group entity's attribute, which decides whether the
	 *                                conditional facet is on
	 * @param conditionalFacetScopes  scopes in which the conditional facet is declared. `ARCHIVED` is what makes
	 *                                the archived half of the trigger — and hence of the lookup — reachable at
	 *                                all; an empty set makes the collection one the trigger can never fire for,
	 *                                which is the state the lookup must never be built in
	 */
	private void prepare(
		@Nonnull CatalogState state,
		@Nonnull String widgetType,
		@Nonnull Set<Scope> conditionalFacetScopes
	) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_TAG).updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER_VALUE).updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER)
					.withAttribute(
						ATTR_INPUT_WIDGET_TYPE, String.class,
						whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					// starts merely filterable so it can be raised later, without a reindex
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					// never written by a passing test: it exists so a write can be refused AFTER every index has
					// already been mutated, which is the only shape in which a rewind has anything to rewind
					.withReferenceToEntity(
						REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(ATTR_TAG_NOTE, String.class, thatIs -> {
							})
					)
					.withReferenceToEntity(
						REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> {
							whichIs
								.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
								.indexedWithComponentsInScope(
									Scope.LIVE,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.indexedWithComponentsInScope(
									Scope.ARCHIVED,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(ENTITY_PARAMETER);
							for (final Scope facetScope : conditionalFacetScopes) {
								whichIs.facetedPartiallyInScope(facetScope, conditionalFacetExpression());
							}
						}
					)
					.updateVia(session);

				for (int pk = 1; pk <= OVER_THRESHOLD; pk++) {
					session.createNewEntity(ENTITY_CATEGORY, pk).upsertVia(session);
				}
				session.createNewEntity(ENTITY_BRAND, BRAND_PK).upsertVia(session);
				session.createNewEntity(ENTITY_TAG, TAG_PK).upsertVia(session);
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
	 * Builds the conditional-facet expression the fixture declares — the group entity's widget type deciding
	 * whether the facet applies.
	 *
	 * @return the parsed expression
	 */
	@Nonnull
	private static Expression conditionalFacetExpression() {
		return ExpressionFactory.parse(
			"$reference.groupEntity?.attributes['" + ATTR_INPUT_WIDGET_TYPE + "'] == 'CHECKBOX'"
		);
	}

	/**
	 * Upserts a run of products under one shared category and the default brand.
	 *
	 * @param fromPk         first product primary key, inclusive
	 * @param toPk           last product primary key, inclusive
	 * @param sharedCategory category every product joins
	 */
	private void upsertProducts(int fromPk, int toPk, int sharedCategory) {
		upsertProducts(fromPk, toPk, sharedCategory, BRAND_PK);
	}

	/**
	 * Upserts a run of products, each filed under one shared category and one brand, and each carrying the
	 * conditionally-faceted reference so the cross-entity trigger reaches it.
	 *
	 * @param fromPk         first product primary key, inclusive
	 * @param toPk           last product primary key, inclusive
	 * @param sharedCategory category every product joins
	 * @param brandPk        brand every product joins
	 */
	private void upsertProducts(int fromPk, int toPk, int sharedCategory, int brandPk) {
		for (int pk = fromPk; pk <= toPk; pk++) {
			final int productPk = pk;
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setReference(REF_CATEGORIES, sharedCategory)
				.setReference(REF_BRAND, brandPk)
				.setReference(
					REF_PARAMETER_VALUES, PARAM_VALUE_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
				)
				.upsertVia(session));
		}
	}

	/**
	 * Runs one unit of write work against the test catalog — a transaction in `ALIVE`, a plain bulk write in
	 * `WARMING_UP`.
	 *
	 * @param work the write to perform
	 */
	private void tx(@Nonnull Consumer<EvitaSessionContract> work) {
		this.evita.updateCatalog(TEST_CATALOG, work);
	}

	/**
	 * Raises `brand` to `FOR_FILTERING_AND_PARTITIONING` exactly as a client would, and **without** the reindex
	 * that would rebuild anything (issue #409).
	 */
	private void raiseBrandToPartitioned() {
		tx(session -> session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
			.openForWrite()
			.withReferenceToEntity(
				REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session));
	}

	/**
	 * Creates the reflected-reference fixture: `category.products` is the indexed, partitioned source, and the
	 * product collection carries a **reflected** counterpart that inherits its index type from it. The product
	 * collection also declares the conditional facet, so its reduced-index membership lookup is maintained.
	 *
	 * The reflected reference deliberately sets no indexing of its own — that is what makes its index type follow
	 * the source reference, and hence what makes a schema change on the other collection change this one.
	 */
	private void prepareReflected() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_PARAMETER_VALUE).updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER)
					.withAttribute(
						ATTR_INPUT_WIDGET_TYPE, String.class,
						whichIs -> whichIs.filterableInScope(Scope.LIVE).nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE)
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.facetedPartiallyInScope(Scope.LIVE, conditionalFacetExpression())
					)
					.withReflectedReferenceToEntity(
						REF_REFLECTED_CATEGORIES, ENTITY_CATEGORY, REF_PRODUCTS,
						whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE)
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withReferenceToEntity(
						REF_PRODUCTS, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
					)
					.updateVia(session);

				session.createNewEntity(ENTITY_PARAMETER_VALUE, PARAM_VALUE_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PARAMETER, PARAMETER_PK)
					.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL_INPUT")
					.upsertVia(session);
				for (int pk = 1; pk <= 5; pk++) {
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setReference(
							REF_PARAMETER_VALUES, PARAM_VALUE_PK,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
						)
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
		// the reflected reference on the products is populated from the category side
		upsertCategoryWithProducts(1, 1, 5);
	}

	/**
	 * Creates a category referencing a run of products, which is what populates the products' reflected reference
	 * — and hence what creates the reduced indexes the product collection's lookup summarises.
	 *
	 * @param categoryPk primary key of the category to create
	 * @param fromPk     first referenced product primary key, inclusive
	 * @param toPk       last referenced product primary key, inclusive
	 */
	private void upsertCategoryWithProducts(int categoryPk, int fromPk, int toPk) {
		tx(session -> {
			final EntityBuilder category = session.createNewEntity(ENTITY_CATEGORY, categoryPk);
			for (int pk = fromPk; pk <= toPk; pk++) {
				category.setReference(REF_PRODUCTS, pk);
			}
			category.upsertVia(session);
		});
	}

	/**
	 * Switches the CATEGORY collection's `products` reference between partitioned and merely filterable. The
	 * product collection's reflected counterpart inherits whichever it is, so this is a schema change on one
	 * collection that changes the indexing of another.
	 *
	 * @param partitioned `true` to index the source reference for filtering and partitioning
	 */
	private void setCategoryProductsIndexing(boolean partitioned) {
		tx(session -> session.getEntitySchemaOrThrowException(ENTITY_CATEGORY)
			.openForWrite()
			.withReferenceToEntity(
				REF_PRODUCTS, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (partitioned) {
						whichIs.indexedForFilteringAndPartitioning();
					} else {
						whichIs.indexedForFiltering();
					}
				}
			)
			.updateVia(session));
	}

	/**
	 * Declares or drops the collection's only conditional facet, which is the gate the whole lookup is maintained
	 * behind — dropping it makes this a collection the cross-entity trigger can never fire for.
	 *
	 * @param declared `true` to declare the conditional facet in `LIVE`, `false` to drop it
	 */
	private void setConditionalFacet(boolean declared) {
		tx(session -> session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
			.openForWrite()
			.withReferenceToEntity(
				REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (declared) {
						whichIs.facetedPartiallyInScope(Scope.LIVE, conditionalFacetExpression());
					} else {
						whichIs.nonFaceted();
					}
				}
			)
			.updateVia(session));
	}

	/**
	 * Switches `categories` between partitioned and merely filterable. Lowering it is what stops the membership
	 * maintenance hooks firing while the reduced indexes keep changing underneath them.
	 *
	 * @param partitioned `true` to index the reference for filtering and partitioning, `false` for filtering only
	 */
	private void setCategoriesIndexing(boolean partitioned) {
		tx(session -> session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
			.openForWrite()
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (partitioned) {
						whichIs.indexedForFilteringAndPartitioning();
					} else {
						whichIs.indexedForFiltering();
					}
				}
			)
			.updateVia(session));
	}

	/**
	 * Fires the cross-entity conditional-facet trigger by making the group entity start satisfying the
	 * expression the faceted reference is conditioned on.
	 */
	private void fireCrossEntityTrigger() {
		tx(session -> session.getEntity(ENTITY_PARAMETER, PARAMETER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
			.upsertVia(session));
	}

	/**
	 * Counts products of the given brand surviving a selection of the conditional facet — the query that is
	 * evaluated against the brand's reduced index, and therefore the one that exposes a facet the trigger
	 * failed to write there.
	 *
	 * @param brandPk primary key of the brand whose partition is queried
	 * @return number of matching products
	 */
	private int countInBrandWithFacetSelected(int brandPk) {
		return countInPartitionWithFacetSelected(REF_BRAND, brandPk);
	}

	/**
	 * Counts products of the given category surviving a selection of the conditional facet.
	 *
	 * @param categoryPk primary key of the category whose partition is queried
	 * @return number of matching products
	 */
	private int countInCategoryWithFacetSelected(int categoryPk) {
		return countInPartitionWithFacetSelected(REF_CATEGORIES, categoryPk);
	}

	/**
	 * Counts products of one partition surviving a selection of the conditional facet — the query that is
	 * evaluated against that partition's reduced index, and therefore the one that exposes a facet the trigger
	 * failed to write there, or wrote there without the owner belonging to the partition.
	 *
	 * @param referenceName  reference whose partition is queried
	 * @param referencedPk   primary key of the referenced entity naming the partition
	 * @return number of matching products
	 */
	private int countInPartitionWithFacetSelected(@Nonnull String referenceName, int referencedPk) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						referenceHaving(referenceName, entityPrimaryKeyInSet(referencedPk)),
						userFilter(facetHaving(REF_PARAMETER_VALUES, entityPrimaryKeyInSet(PARAM_VALUE_PK)))
					),
					require(page(1, 1))
				),
				EntityReference.class
			).getTotalRecordCount()
		);
	}

	/**
	 * Returns the storage primary key of the reduced index holding one referenced entity's partition — the
	 * identity the reverse lookup records, and therefore the one a fabricated entry has to name to be
	 * indistinguishable from a real one.
	 *
	 * @param referenceName reference owning the partition
	 * @param referencedPk  primary key of the referenced entity naming the partition
	 * @return the reduced index's storage primary key
	 */
	private int reducedIndexPkOf(@Nonnull String referenceName, int referencedPk) {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final EntityIndex reducedIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(referenceName, referencedPk))
			)
		);
		assertNotNull(
			reducedIndex,
			"reference `" + referenceName + "` has no reduced index for " + referencedPk
		);
		return reducedIndex.getPrimaryKey();
	}

	/**
	 * Resolves the product collection of the test catalog, which is the collection every assertion here reads
	 * its ground truth out of.
	 *
	 * @return the product collection; never `null`
	 */
	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}
}
