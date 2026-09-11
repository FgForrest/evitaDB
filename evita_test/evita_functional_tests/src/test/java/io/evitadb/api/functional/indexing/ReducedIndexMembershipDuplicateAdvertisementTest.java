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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attacks the premise the reduced-index membership lookup is built on: that a reference advertises each of
 * its reduced indexes **exactly once**.
 *
 * Both sites that fill the lookup — `EntityCollection#registerReducedIndex` at load and
 * `ReferenceIndexMutator#seedFromAdvertisedIndexes` on the first write after a reference is raised — hand
 * every advertised primary key straight to {@link ReducedIndexMembership#registerIndex} /
 * {@link ReducedIndexMembership#registerIndexAsResidual}, both of which **refuse** a key they already hold.
 * A reference that advertised one index twice would therefore not build a slightly wrong lookup: it would
 * take the collection offline at load, or abort the transaction that met it.
 *
 * That premise is structural — a reduced index is filed in its type index under exactly one referenced (or
 * group) primary key — but the structure it rests on is the **group** family, which the rest of the
 * conditional-facet suite exercises only for references that are not partitioned. A reference that is
 * `FOR_FILTERING_AND_PARTITIONING` *and* indexes {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY}
 * owns two families of reduced indexes feeding one lookup, and one {@link io.evitadb.index.ReducedGroupEntityIndex}
 * is shared by every one of an owner's references that resolves to the same group — which is exactly the shape
 * a duplicate advertisement would arise in. This class drives that shape through every event that rewrites an
 * advertisement and asserts the premise directly, rather than waiting for the refusal downstream of it.
 *
 * {@link #assertAdvertisementNeverRepeats()} is the assertion that matters: the completeness check
 * `ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes` reads the advertisement into a
 * {@link Set}, so a repeated key collapses there and cannot be seen.
 *
 * @author Claude (issue #1529 sibling-resolver optimization), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reduced-index membership — the advertisement never repeats a primary key")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(FACET)
class ReducedIndexMembershipDuplicateAdvertisementTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	private static final String REF_CATEGORIES = "categories";
	private static final String REF_PARAMETER_VALUES = "parameterValues";
	private static final String REF_SIBLING_VALUES = "siblingValues";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";

	/**
	 * Parameter (group) entities. Two of them, so a reference can be moved from one group to another — the
	 * event that retires one {@link io.evitadb.index.ReducedGroupEntityIndex} and creates another.
	 */
	private static final int GROUP_A_PK = 100;
	private static final int GROUP_B_PK = 101;

	/**
	 * Parameter values. Three of them share {@link #GROUP_A_PK}, which is what makes one group reduced index
	 * hold the owners of several distinct references — the sharing the removed de-duplication scan was once
	 * justified by.
	 */
	private static final int VALUE_ONE_PK = 10;
	private static final int VALUE_TWO_PK = 11;
	private static final int VALUE_THREE_PK = 12;

	private static final int CATEGORY_COUNT = 3;
	private static final int PRODUCT_COUNT = 6;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReducedIndexMembershipDuplicateAdvertisementTest");
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
	@DisplayName("no event that rewrites an advertisement makes it repeat a primary key")
	void advertisementNeverRepeatsAPrimaryKey(CatalogState state) {
		prepare(state, "CHECKBOX");
		writeTheRichFixture();
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// a reference moved to another group: the owner leaves one group partition and enters another, so both
		// group reduced indexes are rewritten and one of them may be retired outright
		tx(session -> session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_PARAMETER_VALUES, VALUE_ONE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_B_PK)
			)
			.upsertVia(session));
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// a reference removed outright, so its entity partition loses an owner and its group partition may lose
		// one too - but only if no other reference of the same owner resolves to the same group
		tx(session -> session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.removeReference(REF_PARAMETER_VALUES, VALUE_TWO_PK)
			.upsertVia(session));
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// the owner leaves every LIVE index and enters the ARCHIVED ones, which builds a second, independent
		// advertisement in the other scope
		tx(session -> session.archiveEntity(ENTITY_PRODUCT, 3));
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		tx(session -> session.restoreEntity(ENTITY_PRODUCT, 3));
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// the owner disappears entirely
		tx(session -> session.deleteEntity(ENTITY_PRODUCT, 4));
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// ... and comes back under the same primary key, re-creating partitions that were just retired
		upsertProduct(4, 1, VALUE_ONE_PK, GROUP_A_PK);
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("the trigger fans out to a sibling owning both reduced-index families")
	void triggerFansOutToASiblingOwningBothIndexFamilies(CatalogState state) {
		// `ReevaluateExpressionExecutor#probeReducedIndexForAffectedOwners` appends each `(owner, partition)`
		// pair without a duplicate check, on the premise that one reduced index primary key reaches it at most
		// once per sibling reference — which for the unaccelerated walk rests on the two families advertising
		// disjoint primary keys, and for the accelerated one on the covered and residual sets being disjoint.
		// A sibling that is partitioned, grouped and indexes both components is the shape where both halves of
		// that premise are exercised at once, with several owners and a group partition reached through more
		// than one of each owner's references.
		prepare(state, "INTERVAL_INPUT");
		writeTheRichFixture();
		assertFalse(
			isFacetedIn(siblingEntityPartition(VALUE_ONE_PK), VALUE_ONE_PK, 1),
			"the expression does not match yet, so nothing may be faceted before the trigger fires"
		);

		turnWidgetTypeInto("CHECKBOX");

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			assertTrue(
				isFacetedIn(siblingEntityPartition(VALUE_ONE_PK), VALUE_ONE_PK, pk),
				"product " + pk + " must carry the facet in the sibling's entity partition of "
					+ VALUE_ONE_PK
			);
			assertTrue(
				isFacetedIn(siblingEntityPartition(VALUE_TWO_PK), VALUE_TWO_PK, pk),
				"product " + pk + " must carry the facet in the sibling's entity partition of "
					+ VALUE_TWO_PK
			);
			assertTrue(
				isFacetedIn(siblingGroupPartition(), VALUE_ONE_PK, pk),
				"product " + pk + " must carry the facet in the sibling's GROUP partition - the group family "
					+ "is resolved by the same walk and must not be skipped"
			);
		}
	}

	@Test
	@DisplayName("a catalog reloaded with a grouped, partitioned reference rebuilds both families of its lookup")
	void reloadRebuildsBothFamiliesOfTheLookup() {
		// `EntityCollection#rebuildReducedIndexMembership` registers the REFERENCED_ENTITY_TYPE and the
		// REFERENCED_GROUP_ENTITY_TYPE families into ONE lookup, and `ReducedIndexMembership#registerIndex`
		// refuses a primary key it already holds - so a reference owning both families is the shape in which a
		// repeated advertisement would refuse the whole catalog at load rather than merely mis-account for it.
		prepare(CatalogState.ALIVE, "CHECKBOX");
		writeTheRichFixture();
		assertAdvertisementNeverRepeats();

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		// the catalog is loaded on the service pool, so `new Evita` returns while it is still BEING_ACTIVATED
		awaitCatalogLoaded();

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();
		assertBothFamiliesAreAccountedFor();
	}

	@Test
	@DisplayName("warm-up writes, go-live and a reload leave the lookup built exactly once")
	void warmUpWritesThenGoLiveThenReloadKeepTheLookupIntact() {
		// In WARM_UP the lookup is built by the WRITE path, so the collection reaches go-live already holding a
		// slice for every partitioned reference. Go-live hands those indexes to a new catalog instance rather
		// than reloading them; a reload afterwards is the one moment the load-time build meets a collection
		// whose slices were filled by someone else.
		prepare(CatalogState.WARMING_UP, "CHECKBOX");
		writeTheRichFixture();
		assertMembershipAccountsForEveryAdvertisedIndex();

		tx(EvitaSessionContract::goLiveAndClose);

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// a write after go-live, so the transactional half of the maintenance is exercised over a lookup built
		// entirely in warm-up
		upsertProduct(PRODUCT_COUNT + 1, 2, VALUE_THREE_PK, GROUP_B_PK);
		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		awaitCatalogLoaded();

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();
		assertBothFamiliesAreAccountedFor();
	}

	@Test
	@DisplayName("lowering and raising a grouped reference re-seeds its lookup from both families")
	void loweringAndRaisingAGroupedReferenceReSeedsFromBothFamilies() {
		// Lowering the reference below FOR_FILTERING_AND_PARTITIONING discards its lookup
		// (`EntityCollection#discardUnmaintainedReducedIndexMemberships`); raising it back leaves the next write
		// to rebuild one through `ReferenceIndexMutator#seedFromAdvertisedIndexes`, which registers EVERY
		// advertised key of BOTH families as residual, unguarded. That is the only production route into
		// `registerIndexAsResidual` on the write path, and it runs against an advertisement that is already
		// fully populated - the richest input the method ever sees.
		prepare(CatalogState.ALIVE, "CHECKBOX");
		writeTheRichFixture();
		assertNotNull(
			membershipOf(REF_PARAMETER_VALUES, Scope.LIVE),
			"the grouped, partitioned reference must hold a lookup before it is lowered, or the discard below "
				+ "has nothing to discard"
		);

		setParameterValuesPartitioned(false);
		assertTrue(
			membershipOf(REF_PARAMETER_VALUES, Scope.LIVE) == null,
			"lowering the reference must discard its lookup - a frozen lookup trusted again after the raise "
				+ "would make the trigger skip every partition created in between"
		);

		// partitions created while nothing is watching, in both families: a new value under a new group
		upsertProduct(PRODUCT_COUNT + 1, 3, VALUE_THREE_PK, GROUP_B_PK);

		setParameterValuesPartitioned(true);
		// the first write after the raise is what re-seeds the lookup
		upsertProduct(PRODUCT_COUNT + 2, 3, VALUE_THREE_PK, GROUP_B_PK);

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();
		assertBothFamiliesAreAccountedFor();
	}

	@Test
	@DisplayName("re-grouping onto a group that already has a partition keeps the advertisement single-valued")
	void reGroupingOntoAnExistingGroupKeepsTheAdvertisementSingleValued() {
		// The sharing the removed de-duplication scan was justified by, driven directly: several of one owner's
		// references resolve to ONE ReducedGroupEntityIndex. Moving a reference onto a group another of the same
		// owner's references already uses files that one group index under the same group primary key a second
		// time - the closest production comes to advertising an index twice.
		prepare(CatalogState.ALIVE, "CHECKBOX");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, 1)
			.setReference(REF_CATEGORIES, 1)
			.setReference(
				REF_PARAMETER_VALUES, VALUE_ONE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_A_PK)
			)
			.setReference(
				REF_PARAMETER_VALUES, VALUE_TWO_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_B_PK)
			)
			.upsertVia(session));
		assertAdvertisementNeverRepeats();

		// the second reference joins the group the first one is already filed under
		tx(session -> session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_PARAMETER_VALUES, VALUE_TWO_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_A_PK)
			)
			.upsertVia(session));

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		// ... and leaves it again, so the shared group index loses one of its two contributors while keeping the
		// other - the state in which a premature un-advertise would show up
		tx(session -> session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_PARAMETER_VALUES, VALUE_TWO_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_B_PK)
			)
			.upsertVia(session));

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		awaitCatalogLoaded();

		assertAdvertisementNeverRepeats();
		assertMembershipAccountsForEveryAdvertisedIndex();
	}

	/*
		ASSERTIONS
	 */

	/**
	 * Asserts, for every reference of the product collection, in every scope and both reduced-index families,
	 * that {@link ReferencedTypeEntityIndex#forEachReferenceIndexPrimaryKey} emits each primary key **at most
	 * once**.
	 *
	 * This is the premise both fill sites rest on, asserted directly. Counting is what makes it an assertion at
	 * all: every other reader of the advertisement collects it into a {@link Set}, where a repeat is
	 * indistinguishable from a single emission.
	 */
	private void assertAdvertisementNeverRepeats() {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
				for (final EntityIndexType family : ReducedIndexMembership.REFERENCED_TYPE_INDEX_FAMILIES) {
					final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
						new EntityIndexKey(family, scope, reference.getName())
					);
					if (!(typeIndex instanceof final ReferencedTypeEntityIndex typedTypeIndex)) {
						continue;
					}
					final List<Integer> emitted = new ArrayList<>(64);
					typedTypeIndex.forEachReferenceIndexPrimaryKey(emitted::add);
					final Set<Integer> distinct = new TreeSet<>(emitted);
					assertEquals(
						distinct.size(), emitted.size(),
						"scope " + scope + ", reference `" + reference.getName() + "`, family " + family
							+ ": the advertisement emitted " + emitted.size() + " primary keys but only "
							+ distinct.size() + " are distinct (" + emitted + ") - both fill sites hand every "
							+ "emitted key straight to ReducedIndexMembership, which refuses a key it already "
							+ "holds, so a repeat takes the catalog offline at load or aborts the transaction "
							+ "that met it"
					);
				}
			}
		}
	}

	/**
	 * Asserts the invariant the lookup is maintained against — `covered ∪ residual == advertised` — for every
	 * reference that holds one, in every scope.
	 *
	 * An absent lookup is legitimate and skipped: with none the trigger walks the whole advertisement, which is
	 * what it did before the structure existed.
	 */
	private void assertMembershipAccountsForEveryAdvertisedIndex() {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		for (final Scope scope : Scope.values()) {
			final GlobalEntityIndex globalIndex = globalIndexOf(scope);
			if (globalIndex == null) {
				continue;
			}
			for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
				final ReducedIndexMembership membership =
					globalIndex.getReducedIndexMembership(reference.getName());
				if (membership == null) {
					continue;
				}
				final Set<Integer> covered = toSet(membership.getCoveredIndexPrimaryKeys());
				final Set<Integer> residual = toSet(membership.getResidualIndexPrimaryKeys());
				final Set<Integer> known = new TreeSet<>(covered);
				known.addAll(residual);
				assertEquals(
					new TreeSet<>(advertisedReducedIndexes(collection, reference.getName(), scope)), known,
					"scope " + scope + ", reference `" + reference.getName()
						+ "`: covered u residual must equal the advertised indexes"
				);
				assertTrue(
					Collections.disjoint(covered, residual),
					"scope " + scope + ", reference `" + reference.getName()
						+ "`: an index cannot be both covered and residual"
				);
			}
		}
	}

	/**
	 * Asserts that the lookup of the grouped, partitioned reference accounts for indexes of **both** families —
	 * without it every other assertion here would hold vacuously over a group family that was never built.
	 */
	private void assertBothFamiliesAreAccountedFor() {
		final EntityCollection collection = (EntityCollection) getProductCollection();
		final ReducedIndexMembership membership = membershipOf(REF_PARAMETER_VALUES, Scope.LIVE);
		assertNotNull(
			membership,
			"reference `" + REF_PARAMETER_VALUES + "` must hold a lookup, or the family check below asserts "
				+ "nothing"
		);
		for (final EntityIndexType family : ReducedIndexMembership.REFERENCED_TYPE_INDEX_FAMILIES) {
			final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
				new EntityIndexKey(family, Scope.LIVE, REF_PARAMETER_VALUES)
			);
			assertTrue(
				typeIndex instanceof ReferencedTypeEntityIndex,
				"reference `" + REF_PARAMETER_VALUES + "` must own a " + family + " index - it is declared "
					+ "with both indexed components, and a missing family makes the whole premise untested"
			);
			final Set<Integer> advertised = new TreeSet<>();
			((ReferencedTypeEntityIndex) typeIndex).forEachReferenceIndexPrimaryKey(advertised::add);
			assertFalse(
				advertised.isEmpty(),
				"family " + family + " of `" + REF_PARAMETER_VALUES + "` advertises nothing"
			);
			final Set<Integer> known = new TreeSet<>(toSet(membership.getCoveredIndexPrimaryKeys()));
			known.addAll(toSet(membership.getResidualIndexPrimaryKeys()));
			assertTrue(
				known.containsAll(advertised),
				"family " + family + " of `" + REF_PARAMETER_VALUES + "`: the lookup must account for every "
					+ "index this family advertises - " + advertised + " against " + known
			);
		}
	}

	/**
	 * Tells whether the given product is recorded in `index` as a facet of `facetPk` under
	 * {@link #GROUP_A_PK}, for the conditionally faceted reference.
	 *
	 * @param index     the partition to inspect; `null` when it does not exist at all
	 * @param facetPk   primary key of the faceted parameter value
	 * @param productPk primary key of the owner expected to be faceted
	 * @return `true` when the owner carries the facet there
	 */
	private static boolean isFacetedIn(@Nullable EntityIndex index, int facetPk, int productPk) {
		if (index == null) {
			return false;
		}
		final FacetReferenceIndex facetReferenceIndex =
			index.getFacetingEntities().get(REF_PARAMETER_VALUES);
		if (facetReferenceIndex == null) {
			return false;
		}
		final FacetGroupIndex facetGroupIndex = facetReferenceIndex.getFacetsInGroup(GROUP_A_PK);
		if (facetGroupIndex == null) {
			return false;
		}
		final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPk);
		return facetIdIndex != null && facetIdIndex.getRecords().contains(productPk);
	}

	/**
	 * The sibling reference's entity-side partition, keyed by the referenced parameter value.
	 *
	 * @param valuePk primary key of the referenced parameter value naming the partition
	 * @return the partition, or `null` when it does not exist
	 */
	@Nullable
	private EntityIndex siblingEntityPartition(int valuePk) {
		return IndexingTestSupport.getReferencedEntityIndex(
			getProductCollection(), Scope.LIVE, REF_SIBLING_VALUES, valuePk
		);
	}

	/**
	 * The sibling reference's group-side partition, keyed by {@link #GROUP_A_PK}.
	 *
	 * @return the partition, or `null` when it does not exist
	 */
	@Nullable
	private EntityIndex siblingGroupPartition() {
		return IndexingTestSupport.getReferencedGroupEntityIndex(
			getProductCollection(), Scope.LIVE, REF_SIBLING_VALUES, GROUP_A_PK
		);
	}

	/*
		FIXTURE
	 */

	/**
	 * Writes the shape every assertion here needs: several owners spread over a few categories, each carrying
	 * two parameter-value references filed under one shared group, so one group reduced index is reached
	 * through more than one of an owner's references.
	 */
	private void writeTheRichFixture() {
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			final int categoryPk = ((pk - 1) % CATEGORY_COUNT) + 1;
			final int productPk = pk;
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setReference(REF_CATEGORIES, categoryPk)
				.setReference(
					REF_PARAMETER_VALUES, VALUE_ONE_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_A_PK)
				)
				.setReference(
					REF_PARAMETER_VALUES, VALUE_TWO_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_A_PK)
				)
				// two sibling references under ONE group, so the sibling's group reduced index is reached
				// through more than one of this owner's references
				.setReference(
					REF_SIBLING_VALUES, VALUE_ONE_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_A_PK)
				)
				.setReference(
					REF_SIBLING_VALUES, VALUE_TWO_PK,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_A_PK)
				)
				.upsertVia(session));
		}
	}

	/**
	 * Upserts one product with a single category and a single grouped parameter value.
	 *
	 * @param productPk  primary key of the product
	 * @param categoryPk category the product joins
	 * @param valuePk    parameter value the product references
	 * @param groupPk    group the parameter-value reference is filed under
	 */
	private void upsertProduct(int productPk, int categoryPk, int valuePk, int groupPk) {
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, productPk)
			.setReference(REF_CATEGORIES, categoryPk)
			.setReference(
				REF_PARAMETER_VALUES, valuePk,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, groupPk)
			)
			.upsertVia(session));
	}

	/**
	 * Switches the grouped reference between partitioned and merely filterable. Lowering it discards its
	 * lookup; raising it back leaves the next write to re-seed one from the advertisement.
	 *
	 * @param partitioned `true` to index the reference for filtering and partitioning
	 */
	private void setParameterValuesPartitioned(boolean partitioned) {
		tx(session -> session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
			.openForWrite()
			.withReferenceToEntity(
				REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (partitioned) {
						whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED);
					} else {
						whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED);
					}
				}
			)
			.updateVia(session));
	}

	/**
	 * Creates the schema and the fixture entities and brings the catalog to the requested state.
	 *
	 * The reference that carries the conditional facet is itself partitioned **and** grouped **and** indexes
	 * both components, which is the shape in which one lookup is fed by two families of reduced indexes.
	 *
	 * @param state      target catalog state
	 * @param widgetType initial value of the group entity's attribute, which decides whether the facet applies
	 */
	private void prepare(@Nonnull CatalogState state, @Nonnull String widgetType) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
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
					.withReferenceToEntity(
						REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
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
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.facetedPartiallyInScope(Scope.LIVE, conditionalFacetExpression())
							.facetedPartiallyInScope(Scope.ARCHIVED, conditionalFacetExpression())
					)
					// the SIBLING the cross-entity trigger fans out to: same shape - partitioned, grouped, both
					// components - so the resolver's walk over it covers the REFERENCED_ENTITY_TYPE and the
					// REFERENCED_GROUP_ENTITY_TYPE family in one resolution, which is where the claim that the
					// two families advertise disjoint primary keys is actually paid
					.withReferenceToEntity(
						REF_SIBLING_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
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
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					)
					.updateVia(session);

				for (int pk = 1; pk <= CATEGORY_COUNT; pk++) {
					session.createNewEntity(ENTITY_CATEGORY, pk).upsertVia(session);
				}
				for (final int pk : new int[]{VALUE_ONE_PK, VALUE_TWO_PK, VALUE_THREE_PK}) {
					session.createNewEntity(ENTITY_PARAMETER_VALUE, pk).upsertVia(session);
				}
				for (final int pk : new int[]{GROUP_A_PK, GROUP_B_PK}) {
					session.createNewEntity(ENTITY_PARAMETER, pk)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, widgetType)
						.upsertVia(session);
				}

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
	 * Rewrites {@link #GROUP_A_PK}'s attribute, which is what fires the cross-entity conditional-facet trigger
	 * and therefore what drives the sibling resolution this class exercises.
	 *
	 * @param widgetType new value of the group entity's attribute
	 */
	private void turnWidgetTypeInto(@Nonnull String widgetType) {
		tx(session -> session.getEntity(ENTITY_PARAMETER, GROUP_A_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, widgetType)
			.upsertVia(session));
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
	 * Blocks until the reopened catalog has finished loading — `new Evita(...)` returns while the catalog is
	 * still `BEING_ACTIVATED` on the service pool, and the lookup is rebuilt at the very end of that load.
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

	/**
	 * Builds the configuration every instance in this test is opened with. Session inactivity timeout is
	 * disabled so a catalog closed and reopened mid-test cannot lose a session underneath an assertion.
	 *
	 * @return configuration rooted at this test's own temporary paths
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
	 * Reads the reduced indexes a reference advertises, from the reference's own type indexes.
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
		for (final EntityIndexType family : ReducedIndexMembership.REFERENCED_TYPE_INDEX_FAMILIES) {
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
	 * Returns the product collection's global index in the given scope.
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
	 * Resolves the product collection of the test catalog.
	 *
	 * @return the product collection; never `null`
	 */
	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}
}
