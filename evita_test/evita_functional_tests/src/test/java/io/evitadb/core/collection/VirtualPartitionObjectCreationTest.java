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

package io.evitadb.core.collection;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Verifies that a catalog representing its single-owner reference partitions virtually, while it writes them
 * virtually, refuses to create the index object of a partition of a reference whose partitions may be virtual - while
 * an index object that already exists is still answered, and the partitions of a reference indexed for partitioning
 * are created as always. The virtual writing is on by default in a virtual catalog, can be switched off by a test and
 * never on in a catalog with objects, and is carried by the handle every entity mutation of the catalog gets.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Refused creation of virtually written partition objects")
@Tag(ENGINE)
@Tag(REFERENCE)
class VirtualPartitionObjectCreationTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualPartitionObjectCreationTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	/** A reference indexed for filtering - its single-owner partitions are virtual. */
	private static final String REF_CATEGORIES = "categories";
	/** A reference indexed for partitioning - its partitions are never virtual. */
	private static final String REF_PARTITIONED_CATEGORIES = "partitionedCategories";
	/** The category the product references while the virtual writing is switched off. */
	private static final int REFERENCED_CATEGORY_PK = 1;
	/** A category no product references. */
	private static final int UNREFERENCED_CATEGORY_PK = 3;
	/** The product referencing {@link #REFERENCED_CATEGORY_PK}. */
	private static final int PRODUCT_PK = 100;

	private TestPaths paths;
	private Evita evita;

	/**
	 * Creates the key of the partition of the reference for the given category in the live scope.
	 *
	 * @param referenceName the reference
	 * @param categoryPk    primary key of the referenced category
	 * @return the partition key
	 */
	@Nonnull
	private static EntityIndexKey partitionKey(@Nonnull String referenceName, int categoryPk) {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY,
			Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey(referenceName, categoryPk))
		);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualPartitionObjectCreationTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG, true);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		// the product's single-owner partition is written as an index object, the one the tests find existing
		catalog().getVirtualReferencePartitionMaintenance().setWrittenVirtually(false);
		this.evita.updateCatalog(
			CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
					)
					.withReferenceToEntity(
						REF_PARTITIONED_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_CATEGORY, REFERENCED_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, UNREFERENCED_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
					.setReference(REF_CATEGORIES, REFERENCED_CATEGORY_PK)
					.upsertVia(session);
			}
		);
		catalog().getVirtualReferencePartitionMaintenance().setWrittenVirtually(true);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Returns the current instance of the test catalog.
	 *
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
	}

	/**
	 * Returns the current instance of the product collection.
	 *
	 * @return the product collection
	 */
	@Nonnull
	private EntityCollection products() {
		return catalog().getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow(() -> new AssertionError("The test catalog holds no product collection"));
	}

	/**
	 * Runs the logic in a read-write session of the catalog, which writes its single-owner partitions virtually.
	 *
	 * @param logic the logic to run
	 */
	private void updateWrittenVirtually(@Nonnull Consumer<EvitaSessionContract> logic) {
		assertTrue(
			catalog().getVirtualReferencePartitionMaintenance().isWrittenVirtually(),
			"self-check: the catalog must write its single-owner partitions virtually"
		);
		this.evita.updateCatalog(CATALOG, logic);
	}

	@Test
	@DisplayName("the index object of a virtually written partition is not created, an existing one is answered")
	void shouldRefuseCreatingPartitionObjectWhenWrittenVirtually() {
		updateWrittenVirtually(session -> {
			final EntityCollection products = products();
			final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products.getEntityIndexMaintainer();
			final EntityIndexKey existingKey = partitionKey(REF_CATEGORIES, REFERENCED_CATEGORY_PK);
			final EntityIndex existing = products.getIndexByKeyIfExists(existingKey);
			assertNotNull(existing, "self-check: the partition written as an index object must exist");
			assertSame(existing, maintainer.getOrCreateIndex(existingKey));

			final EntityIndexKey missingKey = partitionKey(REF_CATEGORIES, UNREFERENCED_CATEGORY_PK);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> maintainer.getOrCreateIndex(missingKey)
			);
			assertTrue(error.getPrivateMessage().contains("must not be created"), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains(REF_CATEGORIES), error.getPrivateMessage());
			assertNull(products.getIndexByKeyIfExists(missingKey), "the refused index must not exist");
		});
	}

	@Test
	@DisplayName("the index object of a partition of a reference indexed for partitioning is created as always")
	void shouldCreatePartitionObjectOfPartitioningReferenceWhenWrittenVirtually() {
		updateWrittenVirtually(session -> {
			final EntityCollection products = products();
			final EntityIndexKey key = partitionKey(REF_PARTITIONED_CATEGORIES, UNREFERENCED_CATEGORY_PK);
			assertInstanceOf(ReducedEntityIndex.class, products.getEntityIndexMaintainer().getOrCreateIndex(key));
			assertNotNull(products.getIndexByKeyIfExists(key));
		});
	}

	@Test
	@DisplayName("without the virtual writing the index object of the partition is created as always")
	void shouldCreatePartitionObjectWhenNotWrittenVirtually() {
		final VirtualReferencePartitionMaintenance maintenance = catalog().getVirtualReferencePartitionMaintenance();
		maintenance.setWrittenVirtually(false);
		try {
			this.evita.updateCatalog(
				CATALOG,
				session -> {
					final EntityCollection products = products();
					final EntityIndexKey key = partitionKey(REF_CATEGORIES, UNREFERENCED_CATEGORY_PK);
					assertInstanceOf(
						ReducedEntityIndex.class, products.getEntityIndexMaintainer().getOrCreateIndex(key)
					);
				}
			);
		} finally {
			maintenance.setWrittenVirtually(true);
		}
	}

	@Test
	@DisplayName("the virtual writing is on by default and carried by the handle of every entity mutation")
	void shouldCarryVirtualWritingInMutationHandle() {
		final VirtualReferencePartitionMaintenance maintenance = catalog().getVirtualReferencePartitionMaintenance();
		assertTrue(maintenance.isVirtual(), "self-check: the catalog must be virtual");
		assertTrue(maintenance.isWrittenVirtually());
		assertTrue(maintenance.forMutation(ENTITY_PRODUCT, new Object()).writtenVirtually());
		assertTrue(maintenance.forMutation(ENTITY_CATEGORY, new Object()).maintained());
		maintenance.setWrittenVirtually(false);
		try {
			assertFalse(maintenance.isWrittenVirtually());
			assertFalse(maintenance.forMutation(ENTITY_PRODUCT, new Object()).writtenVirtually());
			assertTrue(maintenance.forMutation(ENTITY_CATEGORY, new Object()).maintained());
		} finally {
			maintenance.setWrittenVirtually(true);
		}
		assertTrue(maintenance.forMutation(ENTITY_PRODUCT, new Object()).writtenVirtually());
	}

	@Test
	@DisplayName("the virtual writing cannot be switched on in a catalog representing its partitions as objects")
	void shouldRefuseVirtualWritingInObjectsCatalog() {
		final VirtualReferencePartitionMaintenance maintenance = new VirtualReferencePartitionMaintenance();
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class, () -> maintenance.setWrittenVirtually(true)
		);
		assertTrue(error.getPrivateMessage().contains("as objects"), error.getPrivateMessage());
		assertFalse(maintenance.isWrittenVirtually());
		maintenance.setWrittenVirtually(false);
		assertFalse(maintenance.forMutation(ENTITY_PRODUCT, new Object()).writtenVirtually());
	}

}
