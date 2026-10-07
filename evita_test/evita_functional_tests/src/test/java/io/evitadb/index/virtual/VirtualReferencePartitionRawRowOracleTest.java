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
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link VirtualReferencePartitionRawRowOracle} reports what it claims to. A catalog maintaining the
 * registry from its first write must pass it with every kind of entry engaged; a disagreement is then planted straight
 * into the registry of the warming-up catalog - which the bodies know nothing of - and must be reported.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition raw-row oracle")
@Tag(TEST_HARNESS)
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionRawRowOracleTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionRawRowOracleTest";
	/**
	 * A localized sortable category reference attribute.
	 */
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * A chained category reference attribute.
	 */
	private static final String ATTRIBUTE_CHAIN = "chainOrder";
	/**
	 * A sortable compound of the category reference over the localized label and the order.
	 */
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	/**
	 * The sort key of the order of a category reference.
	 */
	private static final AttributeIndexKey ORDER_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_ORDER, null);
	/**
	 * The sort key of the English label of a category reference.
	 */
	private static final AttributeIndexKey LABEL_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_LABEL, Locale.ENGLISH);
	/**
	 * The sort key of the English compound of the label and the order.
	 */
	private static final AttributeIndexKey COMPOUND_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, COMPOUND_LABEL_AND_ORDER, Locale.ENGLISH);
	/**
	 * The chain key of the chained attribute of a category reference.
	 */
	private static final AttributeIndexKey CHAIN_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_CHAIN, null);

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionRawRowOracleTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG, false);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(CATALOG).orElseThrow();
		for (final String entityType : ENTITY_TYPES) {
			catalog.getVirtualReferencePartitionMaintenance().setMaintained(entityType, true);
		}
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				SingleOwnerPartitionFixture.setUpExtendingCategories(
					session,
					categories -> categories
						.withAttribute(
							ATTRIBUTE_LABEL, String.class,
							thatIs -> thatIs.localized().sortableInScope(Scope.LIVE).nullable()
						)
						.withAttribute(
							ATTRIBUTE_CHAIN, Predecessor.class,
							thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
						)
						.withSortableAttributeCompound(
							COMPOUND_LABEL_AND_ORDER,
							new AttributeElement[]{
								new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
							},
							thatIs -> thatIs.indexedInScope(Scope.LIVE)
						)
				);
				addProduct(session, 7, Predecessor.HEAD);
				addProduct(session, 8, new Predecessor(7));
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a catalog maintained from its first write passes, rows, sort values, compounds and chains engaged")
	void shouldAcceptTheRegistryTheWritersMaintain() {
		assertEquals(List.of(), inspect());

		// not vacuous: every kind of entry the oracle compares is in the registry
		final VirtualReferencePartitionIndex registry = registry();
		assertEquals(PRODUCT_COUNT + 2, registry.getSortIndex(ORDER_KEY).size());
		assertEquals(2, registry.getSortIndex(LABEL_KEY).size());
		assertEquals(2, registry.getSortIndex(COMPOUND_KEY).size());
		assertEquals(-1, registry.getChainPredecessor(CHAIN_KEY, partitionOf(7)).orElseThrow());
		assertEquals(7, registry.getChainPredecessor(CHAIN_KEY, partitionOf(8)).orElseThrow());
	}

	@Test
	@DisplayName("a sort value missing from the registry is reported")
	void shouldReportAMissingSortValue() {
		registry().removeSortValue(ORDER_KEY, partitionOf(4));

		assertOnly("sort key " + ORDER_KEY + " of partition " + partitionOf(4) + ": the registry holds absent");
	}

	@Test
	@DisplayName("a sort value other than the body's is reported")
	void shouldReportAnotherSortValue() {
		registry().putSortValue(ORDER_KEY, partitionOf(4), 44);

		assertOnly("sort key " + ORDER_KEY + " of partition " + partitionOf(4) + ": the registry holds Integer(44)");
	}

	@Test
	@DisplayName("a localized compound value missing from the registry is reported")
	void shouldReportAMissingCompoundValue() {
		registry().removeSortValue(COMPOUND_KEY, partitionOf(8));

		assertOnly("sort key " + COMPOUND_KEY + " of partition " + partitionOf(8) + ": the registry holds absent");
	}

	@Test
	@DisplayName("a chain predecessor missing from the registry is reported")
	void shouldReportAMissingChainPredecessor() {
		registry().removeChainPredecessor(CHAIN_KEY, partitionOf(8));

		assertOnly(
			"chain key " + CHAIN_KEY + " of partition " + partitionOf(8) + ": the registry holds OptionalInt.empty"
		);
	}

	@Test
	@DisplayName("a single owner without its owner row is reported")
	void shouldReportAMissingRow() {
		registry().removePartitionOwner(partitionOf(4));

		assertOnly("partition " + partitionOf(4) + " of ");
	}

	@Test
	@DisplayName("an owner row of a partition with several owners in the bodies is reported")
	void shouldReportARowOfASharedPartition() {
		final int shared = partitionOf(SHARED_CATEGORY);
		registry().putPartitionOwner(shared, 1, SHARED_CATEGORY);

		assertOnly("partition " + shared + " has an owner row, but no single owner in the bodies");
	}

	/**
	 * Adds a product as the fixture creates it, its category reference also carrying an English label and the chained
	 * attribute.
	 *
	 * @param session           the session to write in
	 * @param productPrimaryKey the product, whose category reference has the same primary key
	 * @param predecessor       the chained attribute
	 */
	private static void addProduct(
		@Nonnull EvitaSessionContract session,
		int productPrimaryKey,
		@Nonnull Predecessor predecessor
	) {
		session.upsertEntity(
			newProduct(session, productPrimaryKey).updateReference(
				REFERENCE_CATEGORIES, productPrimaryKey,
				ref -> ref
					.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "label " + productPrimaryKey)
					.setAttribute(ATTRIBUTE_CHAIN, predecessor)
			)
		);
	}

	/**
	 * Asserts the planted disagreement is the only finding.
	 *
	 * @param fragment a fragment the finding must contain
	 */
	private void assertOnly(@Nonnull String fragment) {
		final List<String> findings = inspect();
		assertEquals(1, findings.size(), findings::toString);
		assertTrue(findings.get(0).contains(fragment), findings::toString);
	}

	/**
	 * Inspects the products in a read-only session.
	 *
	 * @return the findings
	 */
	@Nonnull
	private List<String> inspect() {
		return this.evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<String>>) session ->
				VirtualReferencePartitionRawRowOracle.inspect(session, product())
		);
	}

	/**
	 * Resolves the live partition of the category reference to one category.
	 *
	 * @param category the category
	 * @return the partition primary key
	 */
	private int partitionOf(int category) {
		final EntityIndex partition = product().getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category))
			)
		);
		assertNotNull(partition, "No partition for category " + category);
		return partition.getPrimaryKey();
	}

	/**
	 * Returns the live registry of the category reference - written in place, as the catalog is warming up.
	 *
	 * @return the registry
	 */
	@Nonnull
	private VirtualReferencePartitionIndex registry() {
		final EntityIndex typeIndex = product().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
		);
		return assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
	}

	/**
	 * Returns the product collection.
	 *
	 * @return the product collection
	 */
	@Nonnull
	private EntityCollection product() {
		return (EntityCollection) this.evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}
}
