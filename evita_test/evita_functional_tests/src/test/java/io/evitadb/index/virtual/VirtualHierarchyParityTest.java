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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.virtual.VirtualReadParity.Comparison;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the hierarchy routes of a reference answer the same with the read switch on as with it off, on the
 * same engine and the same data (step 4d of #1615): `hierarchyWithin` / `hierarchyWithinRoot` of a reference - both the
 * branch index selection prepares and the one computed when the constraint is nested where index selection does not
 * reach - and the statistics `hierarchyOfReference` counts per node. With the switch on, the registered single-owner
 * partitions of the hierarchy nodes contribute their owners read out of the registry rather than through their reduced
 * entity indexes.
 *
 * The catalog is {@link RelatedProductsShapedFixture}, whose `sections` reference places products in a live and an
 * archived tree of sections where roughly half of the partitions of the nodes are single-owner - singleton leaves, an
 * inner node and a root held by one product each - next to shared ones. Every case runs in the scope sets it admits
 * and must read virtual rows and match an owner in at least one of them; an equal answer of a run that read nothing
 * virtually would compare the object reading with itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of the hierarchy routes over virtual reference partitions")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualHierarchyParityTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualHierarchyParity";
	private static final String SECTIONS = RelatedProductsShapedFixture.REFERENCE_SECTIONS;
	private static final Scope[] LIVE = {Scope.LIVE};
	private static final Scope[] ARCHIVED = {Scope.ARCHIVED};
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[][] SINGLE_SCOPES = {LIVE, ARCHIVED};
	/**
	 * The page large enough to hold every product.
	 */
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;

	/**
	 * A parity case - a filter, optionally a hierarchy requirement, over the products, compared in each of its scope
	 * sets.
	 *
	 * @param name        the name of the case
	 * @param scopeSets   the scope sets the case runs in
	 * @param require     the hierarchy requirement, `null` for a filter-only case
	 * @param constraints the filter constraints following the scope
	 */
	private record ParityCase(
		@Nonnull String name,
		@Nonnull Scope[][] scopeSets,
		@Nullable RequireConstraint require,
		@Nonnull FilterConstraint... constraints
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * The answer of one query - the matching products and the hierarchy computed for them.
	 *
	 * @param primaryKeys the primary keys of the matching products, in primary key order
	 * @param hierarchy   the hierarchy extra result, `null` when the query asked for none
	 */
	private record Answer(@Nonnull List<Integer> primaryKeys, @Nullable Hierarchy hierarchy) {
	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualHierarchyParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		for (final String entityType : RelatedProductsShapedFixture.ENTITY_TYPES) {
			maintenance().setMaintained(entityType, true);
		}
		this.evita.updateCatalog(CATALOG, RelatedProductsShapedFixture::setUp);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(CATALOG, RelatedProductsShapedFixture::archive);
		for (final String entityType : RelatedProductsShapedFixture.ENTITY_TYPES) {
			final EntityCollection collection = (EntityCollection) this.evita.getCatalogInstance(CATALOG)
				.orElseThrow()
				.getCollectionForEntity(entityType)
				.orElseThrow();
			VirtualReferencePartitionOracle.assertConsistent(CATALOG, collection);
		}
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Returns the cases of `hierarchyWithin` / `hierarchyWithinRoot` in the filter. The parent filters name a node of
	 * each tree, so the case reads the live tree in the live scope and the archived one in the archived scope.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> filterCases() {
		final int sharedLiveInner = RelatedProductsShapedFixture.SHARED_LIVE_INNER_SECTION;
		final int singletonLiveInner = RelatedProductsShapedFixture.SINGLETON_LIVE_INNER_SECTION;
		final int archivedRoot = RelatedProductsShapedFixture.ARCHIVED_SECTION_ROOT;
		final int sharedArchivedInner = RelatedProductsShapedFixture.SHARED_ARCHIVED_INNER_SECTION;
		final int singletonArchivedInner = RelatedProductsShapedFixture.SINGLETON_ARCHIVED_INNER_SECTION;
		return Stream.of(
			new ParityCase(
				"hierarchyWithin a root", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, archivedRoot))
			),
			new ParityCase(
				"hierarchyWithin a root over both scopes", new Scope[][]{BOTH}, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, archivedRoot))
			),
			new ParityCase(
				"hierarchyWithin excluding a subtree", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(1, archivedRoot),
					excluding(entityPrimaryKeyInSet(3, sharedArchivedInner))
				)
			),
			new ParityCase(
				"hierarchyWithin excluding by attribute", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(1, archivedRoot),
					excluding(
						attributeInSet(RelatedProductsShapedFixture.ATTRIBUTE_CODE, "S9", "S12", "S16", "S54", "S56")
					)
				)
			),
			new ParityCase(
				"hierarchyWithin excluding the root", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(2, archivedRoot), excludingRoot())
			),
			new ParityCase(
				"hierarchyWithin directRelation of a singleton inner node", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(singletonLiveInner, singletonArchivedInner), directRelation()
				)
			),
			new ParityCase(
				"hierarchyWithin directRelation of a shared and a singleton inner node", SINGLE_SCOPES, null,
				hierarchyWithin(
					SECTIONS,
					entityPrimaryKeyInSet(
						sharedLiveInner, singletonLiveInner, sharedArchivedInner, singletonArchivedInner
					),
					directRelation()
				)
			),
			new ParityCase(
				"hierarchyWithinRoot", SINGLE_SCOPES, null,
				hierarchyWithinRoot(SECTIONS)
			),
			new ParityCase(
				"hierarchyWithinRoot over both scopes", new Scope[][]{BOTH}, null,
				hierarchyWithinRoot(SECTIONS)
			),
			new ParityCase(
				"hierarchyWithinRoot excluding a subtree", SINGLE_SCOPES, null,
				hierarchyWithinRoot(SECTIONS, excluding(entityPrimaryKeyInSet(1, sharedArchivedInner)))
			),
			new ParityCase(
				"hierarchyWithin directRelation of the roots", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, 2, archivedRoot), directRelation())
			),
			new ParityCase(
				"hierarchyWithin and an attribute", SINGLE_SCOPES, null,
				hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(1, archivedRoot)),
				attributeLessThan(RelatedProductsShapedFixture.ATTRIBUTE_RANK, 10_000)
			),
			// nested where index selection does not reach - the computed branch
			new ParityCase(
				"hierarchyWithin inside or", SINGLE_SCOPES, null,
				or(
					hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(3, singletonArchivedInner)),
					entityPrimaryKeyInSet(1, 2)
				)
			),
			new ParityCase(
				"hierarchyWithin directRelation inside or", SINGLE_SCOPES, null,
				or(
					hierarchyWithin(
						SECTIONS, entityPrimaryKeyInSet(singletonLiveInner, singletonArchivedInner), directRelation()
					),
					entityPrimaryKeyInSet(1)
				)
			),
			new ParityCase(
				"hierarchyWithinRoot excluding inside not", SINGLE_SCOPES, null,
				not(hierarchyWithinRoot(SECTIONS, excluding(entityPrimaryKeyInSet(1, sharedArchivedInner))))
			)
		);
	}

	/**
	 * Returns the cases of `hierarchyOfReference` statistics. Each runs in one scope - the requirement admits no other.
	 *
	 * @return the cases
	 */
	@Nonnull
	static Stream<ParityCase> statisticsCases() {
		final int singletonLiveInner = RelatedProductsShapedFixture.SINGLETON_LIVE_INNER_SECTION;
		final int archivedRoot = RelatedProductsShapedFixture.ARCHIVED_SECTION_ROOT;
		final int singletonArchivedInner = RelatedProductsShapedFixture.SINGLETON_ARCHIVED_INNER_SECTION;
		final FilterConstraint withinInnerNode = hierarchyWithin(SECTIONS, entityPrimaryKeyInSet(3, singletonArchivedInner));
		final FilterConstraint lowRank = userFilter(
			attributeLessThan(RelatedProductsShapedFixture.ATTRIBUTE_RANK, 20_000)
		);
		return Stream.of(
			new ParityCase(
				"fromRoot with counts, empty nodes left", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					fromRoot("tree", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
				)
			),
			new ParityCase(
				"fromRoot with counts, empty nodes removed", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					fromRoot("tree", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
				)
			),
			new ParityCase(
				"fromRoot over a user filter, both statistics bases", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					fromRoot(
						"complete",
						statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					),
					fromRoot(
						"withoutUserFilter",
						statistics(StatisticsBase.WITHOUT_USER_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				lowRank
			),
			new ParityCase(
				"children, parents and siblings of an inner node", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					children(
						"children", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
					),
					parents(
						"parents",
						siblings(statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
						statistics(StatisticsType.QUERIED_ENTITY_COUNT)
					),
					siblings("siblings", statistics(StatisticsType.QUERIED_ENTITY_COUNT))
				),
				withinInnerNode
			),
			new ParityCase(
				"children of an inner node over a user filter", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					children(
						"children",
						statistics(StatisticsBase.WITHOUT_USER_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					),
					fromRoot(
						"tree", stopAt(distance(1)),
						statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				withinInnerNode, lowRank
			),
			new ParityCase(
				"children of a singleton inner node", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
					children("children", statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
					parents("parents", statistics(StatisticsType.QUERIED_ENTITY_COUNT))
				),
				hierarchyWithin(
					SECTIONS, entityPrimaryKeyInSet(singletonLiveInner, archivedRoot)
				)
			),
			new ParityCase(
				"siblings of a singleton inner node", SINGLE_SCOPES,
				hierarchyOfReference(
					SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
					siblings("siblings", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
				),
				hierarchyWithin(
					SECTIONS,
					entityPrimaryKeyInSet(singletonLiveInner, singletonArchivedInner)
				)
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("filterCases")
	@DisplayName("hierarchyWithin answers the same owners read virtually")
	void shouldAnswerHierarchyWithinTheSame(@Nonnull ParityCase parityCase) {
		assertParity(parityCase);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("statisticsCases")
	@DisplayName("hierarchyOfReference counts the same owners read virtually")
	void shouldCountHierarchyStatisticsTheSame(@Nonnull ParityCase parityCase) {
		assertParity(parityCase);
	}

	@Test
	@DisplayName("the statistics alone read the singleton nodes virtually")
	void shouldReadTheSingletonNodesInTheStatistics() {
		// no hierarchy constraint in the filter, so every virtual row the read-on run reads is read by the statistics
		for (final Scope[] scopes : SINGLE_SCOPES) {
			final Comparison<Answer> comparison = VirtualReadParity.compare(
				maintenance(), RelatedProductsShapedFixture.ENTITY_TYPES,
				() -> queryProducts(
					scopes,
					hierarchyOfReference(
						SECTIONS, EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY,
						fromRoot("tree", statistics(StatisticsType.QUERIED_ENTITY_COUNT))
					)
				)
			);
			assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
			assertTrue(
				comparison.virtualReading().virtualRowsRead() > 0,
				"The statistics in " + Arrays.toString(scopes) + " must read the singleton nodes virtually"
			);
			final Hierarchy hierarchy = comparison.objectReading().hierarchy();
			assertNotNull(hierarchy);
			// the singleton root of each tree holds exactly its one owner and keeps its node only through it
			final int singletonRoot = scopes[0] == Scope.LIVE ?
				RelatedProductsShapedFixture.SINGLETON_LIVE_ROOT : RelatedProductsShapedFixture.ARCHIVED_SECTION_ROOT;
			assertTrue(
				hierarchy.getReferenceHierarchy(SECTIONS, "tree")
					.stream()
					.anyMatch(it -> it.entity().getPrimaryKey() == singletonRoot &&
						it.queriedEntityCount() != null && it.queriedEntityCount() > 0),
				"The singleton root " + singletonRoot + " must be counted in " + Arrays.toString(scopes)
			);
		}
	}

	/**
	 * Asserts a case answers the same read-off and read-on in each of its scope sets, that the read-on run read virtual
	 * rows and that some scope set matched an owner.
	 *
	 * @param parityCase the case
	 */
	private void assertParity(@Nonnull ParityCase parityCase) {
		long virtualRowsRead = 0;
		boolean matchedAny = false;
		for (final Scope[] scopes : parityCase.scopeSets()) {
			final Comparison<Answer> comparison = VirtualReadParity.compare(
				maintenance(), RelatedProductsShapedFixture.ENTITY_TYPES,
				() -> queryProducts(scopes, parityCase.require(), parityCase.constraints())
			);
			assertEquals(
				comparison.objectReading(), comparison.virtualReading().answer(),
				parityCase + " in " + Arrays.toString(scopes) + " must answer the same with the read switch on"
			);
			virtualRowsRead += comparison.virtualReading().virtualRowsRead();
			matchedAny |= !comparison.objectReading().primaryKeys().isEmpty();
		}
		assertTrue(virtualRowsRead > 0, parityCase + " must read virtual rows in at least one scope set");
		assertTrue(matchedAny, parityCase + " must match an owner in at least one scope set");
	}

	/**
	 * Queries the products matching the constraints in the scopes, with the hierarchy requirement when there is one.
	 *
	 * @param scopes      the scopes
	 * @param require     the hierarchy requirement, `null` for none
	 * @param constraints the filter constraints following the scope
	 * @return the matching products and the hierarchy computed for them
	 */
	@Nonnull
	private Answer queryProducts(
		@Nonnull Scope[] scopes,
		@Nullable RequireConstraint require,
		@Nonnull FilterConstraint... constraints
	) {
		final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
		filter[0] = scope(scopes);
		System.arraycopy(constraints, 0, filter, 1, constraints.length);
		return this.evita.queryCatalog(
			CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(RelatedProductsShapedFixture.ENTITY_PRODUCT),
						filterBy(filter),
						// every alternative index set and every cacheable subtree must answer the same
						require == null ?
							require(page(1, PAGE_SIZE), debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) :
							require(page(1, PAGE_SIZE), require, debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES))
					),
					EntityReference.class
				);
				return new Answer(
					response.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					require == null ? null : response.getExtraResult(Hierarchy.class)
				);
			}
		);
	}

	/**
	 * Returns the switch holder of the catalog.
	 *
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance() {
		return ((Catalog) this.evita.getCatalogInstance(CATALOG).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

}
