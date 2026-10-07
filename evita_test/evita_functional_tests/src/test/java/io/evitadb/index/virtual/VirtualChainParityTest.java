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
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.virtual.VirtualReadParity.Comparison;
import io.evitadb.index.virtual.VirtualReadParity.VirtualReading;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the chains of `Predecessor` and `ReferencedEntityPredecessor` reference attributes answer the same with
 * the read switch on as with it off, on the same engine and the same data: a single-owner partition
 * read virtually is a singleton chain read out of its registry row - on the block route of an entity ordering, and on
 * the by-key route of `referenceContent(orderBy)`, for the original reference and for its reflection, whose chain is
 * read in the other collection, through that collection's registry and read switch.
 *
 * Two catalogs, each maintained from its first write:
 *
 * - {@link ParameterValuesShapedFixture} - every case mixes a virtual singleton with a real chain: the parameter values
 *   shared by every product chain all products, a value of a product's own (or the lonely value) is a single-owner
 *   partition; owner and target primary keys never coincide;
 * - a duplicate-heavy catalog: {@link #OWNER_COUNT} owners each holding two duplicate references to one target, told
 *   apart by their representative attribute - {@link #OWNER_COUNT} times two single-owner partitions of one target,
 *   the shape on which a by-key resolution scanning the family of the target per lookup is quadratic; the target
 *   reflects the reference, so its references are ordered through the owners' partitions - the reflected route.
 *
 * Every positive case must read virtual rows - an equal answer of a run that read nothing virtually would compare the
 * object reading with itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of chains over virtual reference partitions")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualChainParityTest implements EvitaTestSupport {
	private static final String CATALOG_PARAMETERS = "virtualChainParityParameters";
	private static final String CATALOG_DUPLICATES = "virtualChainParityDuplicates";
	private static final String ENTITY_TARGET = "target";
	private static final String ENTITY_OWNER = "owner";
	private static final Set<String> DUPLICATE_ENTITY_TYPES = Set.of(ENTITY_TARGET, ENTITY_OWNER);
	private static final String REFERENCE_ITEMS = "items";
	private static final String REFERENCE_OWNERS = "owners";
	private static final String ATTRIBUTE_KIND = "kind";
	private static final String ATTRIBUTE_ORDER = "order";
	/**
	 * The only target of the duplicate-heavy catalog.
	 */
	private static final int TARGET = 7;
	/**
	 * How many owners hold two duplicate references to {@link #TARGET}.
	 */
	private static final int OWNER_COUNT = 400;
	/**
	 * The page large enough to hold every entity of every catalog.
	 */
	private static final int PAGE_SIZE = 100_000;

	private TestPaths paths;
	private Evita evita;

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualChainParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		build(
			CATALOG_PARAMETERS, ParameterValuesShapedFixture.ENTITY_TYPES,
			ParameterValuesShapedFixture::setUp, session -> {
			}
		);
		build(CATALOG_DUPLICATES, DUPLICATE_ENTITY_TYPES, VirtualChainParityTest::setUpDuplicates, session -> {
		});
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("should order the owners of a virtual singleton and a real chain identically on the block route")
	void shouldOrderBlocksOfVirtualSingletonAndRealChainIdentically() {
		final int ownValueOfProduct3 = ParameterValuesShapedFixture.FIRST_OWN_VALUE +
			2 * ParameterValuesShapedFixture.ROWS_PER_PRODUCT;
		final List<String> differences = new ArrayList<>(16);
		long virtualRowsRead = 0;
		for (String attribute : new String[]{
			ParameterValuesShapedFixture.ATTRIBUTE_ORDER, ParameterValuesShapedFixture.ATTRIBUTE_POSITION
		}) {
			for (OrderDirection targetDirection : OrderDirection.values()) {
				for (OrderDirection direction : OrderDirection.values()) {
					// the reflected side: products in the blocks of parameter values
					final OrderConstraint productOrdering = referenceProperty(
						ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
						traverseByEntityProperty(entityPrimaryKeyNatural(targetDirection)),
						attributeNatural(attribute, direction)
					);
					final FilterConstraint productSelection = referenceHaving(
						ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
						entityPrimaryKeyInSet(
							ParameterValuesShapedFixture.FIRST_SHARED_VALUE, ownValueOfProduct3,
							ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE
						)
					);
					// the original side: parameter values in the blocks of products
					final OrderConstraint valueOrdering = referenceProperty(
						ParameterValuesShapedFixture.REFERENCE_PRODUCTS,
						traverseByEntityProperty(entityPrimaryKeyNatural(targetDirection)),
						attributeNatural(attribute, direction)
					);
					final FilterConstraint valueSelection = referenceHaving(
						ParameterValuesShapedFixture.REFERENCE_PRODUCTS,
						entityPrimaryKeyInSet(ParameterValuesShapedFixture.LONELY_OWNER, 1)
					);
					for (Scope scope : Scope.values()) {
						for (String[] query : new String[][]{
							{ParameterValuesShapedFixture.ENTITY_PRODUCT, "product"},
							{ParameterValuesShapedFixture.ENTITY_PARAMETER_VALUE, "value"}
						}) {
							final boolean products = "product".equals(query[1]);
							final Comparison<List<Integer>> comparison = VirtualReadParity.compare(
								maintenance(CATALOG_PARAMETERS), ParameterValuesShapedFixture.ENTITY_TYPES,
								() -> queryPrimaryKeys(
									query[0], scope, products ? productSelection : valueSelection,
									products ? productOrdering : valueOrdering
								)
							);
							if (!comparison.objectReading().equals(comparison.virtualReading().answer())) {
								differences.add(
									attribute + " " + query[1] + " " + scope + " targets " + targetDirection + " " +
										direction + ": " + comparison.objectReading() + " vs " +
										comparison.virtualReading().answer()
								);
							}
							virtualRowsRead += comparison.virtualReading().virtualRowsRead();
						}
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(virtualRowsRead > 0, "The block route must have read virtual rows.");
	}

	@Test
	@DisplayName("should order the references of a virtual singleton and a real chain identically, original and reflected")
	void shouldOrderReferenceContentChainsIdentically() {
		final int ownValueOfProduct1 = ParameterValuesShapedFixture.FIRST_OWN_VALUE;
		final List<String> differences = new ArrayList<>(16);
		long virtualRowsRead = 0;
		for (String attribute : new String[]{
			ParameterValuesShapedFixture.ATTRIBUTE_ORDER, ParameterValuesShapedFixture.ATTRIBUTE_POSITION
		}) {
			for (OrderDirection direction : OrderDirection.values()) {
				for (Scope scope : Scope.values()) {
					// the original side reads its own partitions keyed by the products
					final Comparison<Map<Integer, List<Integer>>> original = VirtualReadParity.compare(
						maintenance(CATALOG_PARAMETERS), ParameterValuesShapedFixture.ENTITY_TYPES,
						() -> queryReferenceOrder(
							CATALOG_PARAMETERS, ParameterValuesShapedFixture.ENTITY_PARAMETER_VALUE, scope,
							entityPrimaryKeyInSet(
								ParameterValuesShapedFixture.FIRST_SHARED_VALUE,
								ParameterValuesShapedFixture.FIRST_SHARED_VALUE + 1,
								ownValueOfProduct1, ownValueOfProduct1 + 1,
								ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE,
								ParameterValuesShapedFixture.MIXED_PARAMETER_VALUE
							),
							ParameterValuesShapedFixture.REFERENCE_PRODUCTS, attributeNatural(attribute, direction)
						)
					);
					// the reflected side reads the partitions of the parameter values keyed by the product
					final Comparison<Map<Integer, List<Integer>>> reflected = VirtualReadParity.compare(
						maintenance(CATALOG_PARAMETERS), ParameterValuesShapedFixture.ENTITY_TYPES,
						() -> queryReferenceOrder(
							CATALOG_PARAMETERS, ParameterValuesShapedFixture.ENTITY_PRODUCT, scope,
							entityPrimaryKeyInSet(1, 3, 5, ParameterValuesShapedFixture.LONELY_OWNER),
							ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
							attributeNatural(attribute, direction)
						)
					);
					for (Comparison<Map<Integer, List<Integer>>> comparison : List.of(original, reflected)) {
						if (!comparison.objectReading().equals(comparison.virtualReading().answer())) {
							differences.add(
								attribute + " " + scope + " " + direction + ": " + comparison.objectReading() +
									" vs " + comparison.virtualReading().answer()
							);
						}
						virtualRowsRead += comparison.virtualReading().virtualRowsRead();
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(virtualRowsRead > 0, "The by-key route must have read virtual rows.");
	}

	@Test
	@DisplayName("should order the reflected references of duplicate single-owner partitions identically")
	void shouldOrderReflectedReferenceContentOfDuplicatesIdentically() {
		for (OrderDirection direction : OrderDirection.values()) {
			final Comparison<Map<Integer, List<Integer>>> comparison = VirtualReadParity.compare(
				maintenance(CATALOG_DUPLICATES), DUPLICATE_ENTITY_TYPES,
				() -> queryReferenceOrder(
					CATALOG_DUPLICATES, ENTITY_TARGET, Scope.LIVE, null, REFERENCE_OWNERS,
					attributeNatural(ATTRIBUTE_ORDER, direction)
				)
			);
			assertEquals(2 * OWNER_COUNT, comparison.objectReading().get(TARGET).size());
			assertEquals(comparison.objectReading(), comparison.virtualReading().answer());
			assertTrue(comparison.virtualReading().virtualRowsRead() > 0, "The reflected route must read virtual rows.");
		}
	}

	@Test
	@DisplayName("should build the family of a target once per query, visiting its partitions linearly")
	void shouldBuildFamilyOfTargetOncePerQuery() {
		final VirtualReferencePartitionMaintenance maintenance = maintenance(CATALOG_DUPLICATES);
		final Comparison<Map<Integer, List<Integer>>> comparison = VirtualReadParity.compare(
			maintenance, DUPLICATE_ENTITY_TYPES,
			() -> queryReferenceOrder(
				CATALOG_DUPLICATES, ENTITY_OWNER, Scope.LIVE, null, REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER)
			)
		);
		assertEquals(OWNER_COUNT, comparison.objectReading().size());
		assertEquals(comparison.objectReading(), comparison.virtualReading().answer());

		final long buildsBefore = maintenance.getPartitionKeyFamilyBuilds();
		final long visitsBefore = maintenance.getPartitionKeyVisitedEntries();
		final VirtualReading<Map<Integer, List<Integer>>> reading = VirtualReadParity.readVirtually(
			maintenance, DUPLICATE_ENTITY_TYPES,
			() -> queryReferenceOrder(
				CATALOG_DUPLICATES, ENTITY_OWNER, Scope.LIVE, null, REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER)
			)
		);
		assertTrue(reading.virtualRowsRead() > 0, "The by-key route must have read virtual rows.");
		assertEquals(
			1, maintenance.getPartitionKeyFamilyBuilds() - buildsBefore,
			"The family of the only target must be built once per query."
		);
		assertEquals(
			2L * OWNER_COUNT, maintenance.getPartitionKeyVisitedEntries() - visitsBefore,
			"Building the family must visit each of its partitions once."
		);
	}

	/**
	 * Fills the duplicate-heavy catalog.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpDuplicates(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_OWNER)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					.withAttribute(
						ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).representative()
					)
					.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, thatIs -> thatIs.sortableInScope(Scope.LIVE))
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_TARGET)
			.withReflectedReferenceToEntity(
				REFERENCE_OWNERS, ENTITY_OWNER, REFERENCE_ITEMS,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE)
			)
			.updateVia(session);
		session.createNewEntity(ENTITY_TARGET, TARGET).upsertVia(session);
		for (int owner = 1; owner <= OWNER_COUNT; owner++) {
			final int theOwner = owner;
			session.createNewEntity(ENTITY_OWNER, 1_000 + owner)
				.setOrUpdateReference(
					REFERENCE_ITEMS, TARGET, Functions.alwaysFalse(),
					whichIs -> whichIs.setAttribute(ATTRIBUTE_KIND, "a" + theOwner)
						.setAttribute(ATTRIBUTE_ORDER, Predecessor.HEAD)
				)
				.setOrUpdateReference(
					REFERENCE_ITEMS, TARGET, Functions.alwaysFalse(),
					whichIs -> whichIs.setAttribute(ATTRIBUTE_KIND, "b" + theOwner)
						.setAttribute(ATTRIBUTE_ORDER, Predecessor.HEAD)
				)
				.upsertVia(session);
		}
	}

	/**
	 * Queries a collection of the parameter values catalog in the scope on the index route and returns the primary
	 * keys.
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull FilterConstraint selection,
		@Nonnull OrderConstraint ordering
	) {
		return this.evita.queryCatalog(
			CATALOG_PARAMETERS,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(
						collection(entityType),
						filterBy(scope(scope), selection),
						orderBy(ordering),
						require(page(1, PAGE_SIZE), debug(DebugMode.PREFER_INDEX_SCAN))
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.map(EntityReference::getPrimaryKey)
				.toList()
		);
	}

	/**
	 * Queries entities of a collection in the scope and returns the referenced primary keys of each in the order the
	 * `referenceContent(orderBy)` sorted them.
	 */
	@Nonnull
	private Map<Integer, List<Integer>> queryReferenceOrder(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nullable FilterConstraint selection,
		@Nonnull String referenceName,
		@Nonnull OrderConstraint ordering
	) {
		return this.evita.queryCatalog(
			catalog,
			(Function<EvitaSessionContract, Map<Integer, List<Integer>>>) session -> {
				final Map<Integer, List<Integer>> result = CollectionUtils.createLinkedHashMap(64);
				for (SealedEntity entity : session.queryList(
					query(
						collection(entityType),
						filterBy(scope(scope), selection),
						require(
							page(1, PAGE_SIZE),
							entityFetch(referenceContentWithAttributes(referenceName, orderBy(ordering)))
						)
					),
					SealedEntity.class
				)) {
					result.put(
						entity.getPrimaryKeyOrThrowException(),
						entity.getReferences(referenceName)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.toList()
					);
				}
				return result;
			}
		);
	}

	/**
	 * Creates a catalog maintained from its first write, fills it in warm-up, takes it live and then applies the
	 * changes of the live catalog.
	 *
	 * @param catalog     the catalog
	 * @param entityTypes its collections, all maintained
	 * @param warmUp      fills the catalog in warm-up
	 * @param live        changes the live catalog
	 */
	private void build(
		@Nonnull String catalog,
		@Nonnull Set<String> entityTypes,
		@Nonnull Consumer<EvitaSessionContract> warmUp,
		@Nonnull Consumer<EvitaSessionContract> live
	) {
		this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
		for (final String entityType : entityTypes) {
			maintenance(catalog).setMaintained(entityType, true);
		}
		this.evita.updateCatalog(catalog, warmUp);
		this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(catalog, live);
		for (final String entityType : entityTypes) {
			final EntityCollection collection = (EntityCollection) this.evita.getCatalogInstance(catalog)
				.orElseThrow()
				.getCollectionForEntity(entityType)
				.orElseThrow();
			VirtualReferencePartitionOracle.assertConsistent(catalog, collection);
		}
	}

	/**
	 * Returns the switch holder of a catalog.
	 *
	 * @param catalog the catalog
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance(@Nonnull String catalog) {
		return ((Catalog) this.evita.getCatalogInstance(catalog).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

}
