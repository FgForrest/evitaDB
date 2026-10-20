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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a `BigDecimalNumberRange` element of a sortable attribute compound is encoded the same way no matter
 * which write path builds the compound entry.
 *
 * A range compares by its bounds encoded as longs at some decimal scale, so two ranges are only comparable when they
 * are encoded at the same one. The attribute schema's `indexedDecimalPlaces` is that common scale, while a range
 * created from plain `BigDecimal` bounds carries the scale of those bounds. A compound entry is built either from the
 * value of the mutation being applied, or from the values stored in the entity body - when an entity enters an index
 * (a reference creating a partition, a restore from the archive), when it leaves one (deletion), and for every element
 * of the compound other than the one being changed. Both paths must encode the range at the schema scale, otherwise
 * entries of different entities order by incomparable longs and an entry inserted by one path cannot be found and
 * removed by the other.
 *
 * Every scenario stores ranges whose bounds have one decimal place. The mismatching configuration indexes them at two
 * decimal places, the control at one - where both encodings coincide, so the control must pass with or without the
 * fix and proves the scenarios themselves are sound.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range element of a sortable compound encoded at the schema scale")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(ORDER)
class RangeCompoundScaleConsistencyTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REF_BRAND = "brand";
	private static final String ATTR_VALIDITY = "validity";
	private static final String ATTR_CODE = "code";
	private static final String COMPOUND_VALIDITY_CODE = "validityCode";
	/**
	 * The decimal places of every range bound the scenarios store.
	 */
	private static final int INTRINSIC_SCALE = 1;
	/**
	 * Products ordered by their range - the expected ascending order is 3, 2, 1.
	 */
	private static final int[] PRODUCTS = {1, 2, 3};
	private static final int[] EXPECTED_ORDER = {3, 2, 1};

	private TestPaths paths;
	private Evita evita;

	/**
	 * Provides every combination of the catalog state and the schema scale - the scale mismatching the intrinsic scale
	 * of the stored ranges, and the matching control.
	 *
	 * @return arguments of the parameterized scenarios
	 */
	@Nonnull
	static Stream<Arguments> stateAndScale() {
		return Stream.of(
			Arguments.of(CatalogState.WARMING_UP, INTRINSIC_SCALE + 1),
			Arguments.of(CatalogState.ALIVE, INTRINSIC_SCALE + 1),
			Arguments.of(CatalogState.WARMING_UP, INTRINSIC_SCALE),
			Arguments.of(CatalogState.ALIVE, INTRINSIC_SCALE)
		);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RangeCompoundScaleConsistencyTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}, schema scale {1}")
	@MethodSource("stateAndScale")
	@DisplayName("entries built from the entity body order together with entries built from mutations")
	void shouldOrderEntriesRestoredFromArchiveWithUpsertedEntries(CatalogState state, int schemaScale) {
		prepare(state, schemaScale);
		for (int pk : PRODUCTS) {
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, pk)
				.setAttribute(ATTR_CODE, codeOf(pk))
				.setAttribute(ATTR_VALIDITY, validityOf(pk))
				.upsertVia(session));
		}
		// the restore rebuilds the compound entry of the product from its stored body
		tx(session -> session.archiveEntity(ENTITY_PRODUCT, 1));
		tx(session -> session.restoreEntity(ENTITY_PRODUCT, 1));

		assertArrayEquals(EXPECTED_ORDER, orderedByEntityCompound(), "products ordered by the compound");
		assertArrayEquals(EXPECTED_ORDER, entityCompoundOf(globalIndex()).getSortedRecords(), "global compound index");
	}

	@ParameterizedTest(name = "{0}, schema scale {1}")
	@MethodSource("stateAndScale")
	@DisplayName("another element of the compound changes after the range was set by an update")
	void shouldUpdateOtherElementAfterRangeWasUpdated(CatalogState state, int schemaScale) {
		prepare(state, schemaScale);
		for (int pk : PRODUCTS) {
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, pk)
				.setAttribute(ATTR_CODE, codeOf(pk))
				.upsertVia(session));
		}
		for (int pk : PRODUCTS) {
			tx(session -> fetchProduct(session, pk)
				.openForWrite()
				.setAttribute(ATTR_VALIDITY, validityOf(pk))
				.upsertVia(session));
		}
		// replacing the compound entry looks the old one up through the range read back from the entity body
		for (int pk : PRODUCTS) {
			tx(session -> fetchProduct(session, pk)
				.openForWrite()
				.setAttribute(ATTR_CODE, codeOf(pk) + "-changed")
				.upsertVia(session));
		}

		assertArrayEquals(EXPECTED_ORDER, orderedByEntityCompound(), "products ordered by the compound");
		assertArrayEquals(EXPECTED_ORDER, entityCompoundOf(globalIndex()).getSortedRecords(), "global compound index");

		// the entries are persisted in the encoding they were built with, so the reloaded ones must still be found
		reload();
		assertArrayEquals(EXPECTED_ORDER, orderedByEntityCompound(), "reloaded products ordered by the compound");
		tx(session -> session.deleteEntity(ENTITY_PRODUCT, 1));
		assertArrayEquals(new int[]{3, 2}, entityCompoundOf(globalIndex()).getSortedRecords(), "global compound index");
	}

	@ParameterizedTest(name = "{0}, schema scale {1}")
	@MethodSource("stateAndScale")
	@DisplayName("an entity whose range was set by an update is removed without residue")
	void shouldLeaveNoResidueWhenEntityWithUpdatedRangeIsRemoved(CatalogState state, int schemaScale) {
		prepare(state, schemaScale);
		for (int pk : PRODUCTS) {
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, pk)
				.setAttribute(ATTR_CODE, codeOf(pk))
				.upsertVia(session));
		}
		tx(session -> fetchProduct(session, 1)
			.openForWrite()
			.setAttribute(ATTR_VALIDITY, validityOf(1))
			.upsertVia(session));

		// the removal looks the compound entry up through the range read back from the entity body
		tx(session -> session.deleteEntity(ENTITY_PRODUCT, 1));

		assertArrayEquals(new int[]{2, 3}, entityCompoundOf(globalIndex()).getSortedRecords(), "global compound index");
	}

	@ParameterizedTest(name = "{0}, schema scale {1}")
	@MethodSource("stateAndScale")
	@DisplayName("a partition populated from an existing owner's body orders it among owners indexed by mutations")
	void shouldOrderEntityCompoundOfPartitionPopulatedFromOwnerBody(CatalogState state, int schemaScale) {
		prepare(state, schemaScale);
		for (int pk : PRODUCTS) {
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, pk)
				.setAttribute(ATTR_CODE, codeOf(pk))
				.setReference(REF_BRAND, pk == 1 ? 2 : 1)
				.upsertVia(session));
		}
		for (int pk : PRODUCTS) {
			tx(session -> fetchProduct(session, pk)
				.openForWrite()
				.setAttribute(ATTR_VALIDITY, validityOf(pk))
				.upsertVia(session));
		}
		// the first product enters the partition of the first brand, which indexes it from its stored body
		tx(session -> fetchProduct(session, 1)
			.openForWrite()
			.setReference(REF_BRAND, 1)
			.upsertVia(session));

		final EntityIndex partition = partitionOf(1);
		assertNotNull(partition, "the partition of the first brand must exist");
		assertArrayEquals(EXPECTED_ORDER, entityCompoundOf(partition).getSortedRecords(), "partition compound index");

		// leaving the partition looks the entry up through the range read back from the entity body
		tx(session -> fetchProduct(session, 3)
			.openForWrite()
			.removeReference(REF_BRAND, 1)
			.upsertVia(session));
		assertArrayEquals(new int[]{2, 1}, entityCompoundOf(partitionOf(1)).getSortedRecords(), "partition compound index");
	}

	@ParameterizedTest(name = "{0}, schema scale {1}")
	@MethodSource("stateAndScale")
	@DisplayName("a reference compound mixes entries populated from the body with entries built by an update")
	void shouldOrderReferenceCompoundAfterReferenceRangeWasUpdated(CatalogState state, int schemaScale) {
		prepare(state, schemaScale);
		for (int pk : PRODUCTS) {
			tx(session -> session.createNewEntity(ENTITY_PRODUCT, pk)
				.setReference(
					REF_BRAND, 1,
					whichIs -> whichIs
						.setAttribute(ATTR_CODE, codeOf(pk))
						.setAttribute(ATTR_VALIDITY, validityOf(pk))
				)
				.upsertVia(session));
		}
		// the update of the range rebuilds the compound entry of the last product from the mutation
		tx(session -> fetchProduct(session, 3)
			.openForWrite()
			.setReference(
				REF_BRAND, 1,
				whichIs -> whichIs.setAttribute(ATTR_VALIDITY, validityOf(3))
			)
			.upsertVia(session));

		assertArrayEquals(EXPECTED_ORDER, referenceCompoundOf(partitionOf(1)).getSortedRecords(), "partition compound index");
		assertArrayEquals(EXPECTED_ORDER, orderedByReferenceCompound(), "products ordered by the reference compound");

		// replacing the entry built by the update looks it up through the range read back from the entity body
		tx(session -> fetchProduct(session, 3)
			.openForWrite()
			.setReference(
				REF_BRAND, 1,
				whichIs -> whichIs.setAttribute(ATTR_CODE, codeOf(3) + "-changed")
			)
			.upsertVia(session));
		tx(session -> fetchProduct(session, 3)
			.openForWrite()
			.removeReference(REF_BRAND, 1)
			.upsertVia(session));
		assertArrayEquals(
			new int[]{2, 1},
			referenceCompoundOf(partitionOf(1)).getSortedRecords(),
			"partition compound index"
		);
	}

	/**
	 * Returns the range of the given product. The bounds carry {@link #INTRINSIC_SCALE} decimal places and the ranges
	 * ascend in the order 3, 2, 1. The ranges are chosen so that encoding some of them at one scale and the others at
	 * another changes their order.
	 *
	 * @param productPk primary key of the product
	 * @return the range of the product
	 */
	@Nonnull
	private static BigDecimalNumberRange validityOf(int productPk) {
		return switch (productPk) {
			case 1 -> BigDecimalNumberRange.between(new BigDecimal("3.4"), new BigDecimal("4.5"));
			case 2 -> BigDecimalNumberRange.between(new BigDecimal("2.2"), new BigDecimal("2.5"));
			case 3 -> BigDecimalNumberRange.between(new BigDecimal("1.2"), new BigDecimal("1.3"));
			default -> throw new IllegalArgumentException("Unexpected product " + productPk);
		};
	}

	/**
	 * Returns the code of the given product.
	 *
	 * @param productPk primary key of the product
	 * @return the code of the product
	 */
	@Nonnull
	private static String codeOf(int productPk) {
		return "code" + productPk;
	}

	/**
	 * Fetches a product with all its attributes and its references.
	 *
	 * @param session   the session to read in
	 * @param productPk primary key of the product
	 * @return the product
	 */
	@Nonnull
	private static SealedEntity fetchProduct(@Nonnull EvitaSessionContract session, int productPk) {
		return session.getEntity(ENTITY_PRODUCT, productPk, attributeContentAll(), referenceContentAllWithAttributes())
			.orElseThrow();
	}

	/**
	 * Queries all products ordered by the entity compound.
	 *
	 * @return primary keys of the products in the order of the compound
	 */
	@Nonnull
	private int[] orderedByEntityCompound() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, int[]>) session -> session.queryEntityReference(
					query(
						collection(ENTITY_PRODUCT),
						orderBy(attributeNatural(COMPOUND_VALIDITY_CODE, OrderDirection.ASC))
					)
				)
				.getRecordData()
				.stream()
				.mapToInt(EntityReferenceContract::getPrimaryKeyOrThrowException)
				.toArray()
		);
	}

	/**
	 * Queries the products referencing the first brand ordered by the reference compound.
	 *
	 * @return primary keys of the products in the order of the compound
	 */
	@Nonnull
	private int[] orderedByReferenceCompound() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, int[]>) session -> session.queryEntityReference(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(referenceHaving(REF_BRAND, entityPrimaryKeyInSet(1))),
						orderBy(
							referenceProperty(
								REF_BRAND, attributeNatural(COMPOUND_VALIDITY_CODE, OrderDirection.ASC)
							)
						)
					)
				)
				.getRecordData()
				.stream()
				.mapToInt(EntityReferenceContract::getPrimaryKeyOrThrowException)
				.toArray()
		);
	}

	/**
	 * Returns the sort index of the entity compound in the given index.
	 *
	 * @param index the index holding the compound
	 * @return the sort index of the compound
	 */
	@Nonnull
	private static SortIndex entityCompoundOf(@Nonnull EntityIndex index) {
		final SortIndex sortIndex = index.getSortIndex(new AttributeIndexKey(null, COMPOUND_VALIDITY_CODE, null));
		assertNotNull(sortIndex, "the entity compound must be indexed");
		return sortIndex;
	}

	/**
	 * Returns the sort index of the reference compound in the given partition.
	 *
	 * @param partition the partition holding the compound
	 * @return the sort index of the compound
	 */
	@Nonnull
	private static SortIndex referenceCompoundOf(@Nullable EntityIndex partition) {
		assertNotNull(partition, "the partition must exist");
		final SortIndex sortIndex = partition.getSortIndex(
			new AttributeIndexKey(REF_BRAND, COMPOUND_VALIDITY_CODE, null)
		);
		assertNotNull(sortIndex, "the reference compound must be indexed");
		return sortIndex;
	}

	/**
	 * Resolves the global index of the product collection.
	 *
	 * @return the global index
	 */
	@Nonnull
	private EntityIndex globalIndex() {
		final EntityIndex index = productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		assertNotNull(index, "the global index must exist");
		return index;
	}

	/**
	 * Resolves the partition of the given brand in the product collection.
	 *
	 * @param brandPk primary key of the brand
	 * @return the partition, or `null` when it does not exist
	 */
	@Nullable
	private EntityIndex partitionOf(int brandPk) {
		return productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REF_BRAND, brandPk))
			)
		);
	}

	/**
	 * Resolves the live product collection.
	 *
	 * @return the product collection
	 */
	@Nonnull
	private EntityCollection productCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return (EntityCollection) catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}

	/**
	 * Creates the schema and the brands and brings the catalog to the requested state. The product carries the range
	 * and the code both on itself and on its reference to the brand, each pair forming a sortable compound.
	 *
	 * @param state       target catalog state
	 * @param schemaScale decimal places the range attributes are indexed with
	 */
	private void prepare(@Nonnull CatalogState state, int schemaScale) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withAttribute(
						ATTR_VALIDITY, BigDecimalNumberRange.class,
						thatIs -> thatIs.nullable().indexDecimalPlaces(schemaScale)
					)
					.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::nullable)
					.withSortableAttributeCompound(
						COMPOUND_VALIDITY_CODE,
						new AttributeElement(ATTR_VALIDITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTR_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTR_VALIDITY, BigDecimalNumberRange.class,
								thatIs -> thatIs.nullable().indexDecimalPlaces(schemaScale)
							)
							.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::nullable)
							.withSortableAttributeCompound(
								COMPOUND_VALIDITY_CODE,
								new AttributeElement(ATTR_VALIDITY, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTR_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_BRAND, 1).upsertVia(session);
				session.createNewEntity(ENTITY_BRAND, 2).upsertVia(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Closes the engine and opens it again over the same storage, waiting until the catalog is loaded.
	 */
	private void reload() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
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
	 * Runs one unit of write work against the test catalog - a transaction in `ALIVE`, a plain bulk write in
	 * `WARMING_UP`.
	 *
	 * @param work the write to perform
	 */
	private void tx(@Nonnull Consumer<EvitaSessionContract> work) {
		this.evita.updateCatalog(TEST_CATALOG, work);
	}

	/**
	 * Builds the engine configuration over the test folder, with sessions that never expire.
	 *
	 * @return the configuration
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
}
