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
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.virtual.VirtualPartitionInvariants;
import io.evitadb.index.virtual.VirtualPartitionInvariants.RegisteredRow;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a collection refuses to create the index object of a partition of a reference whose single-owner
 * partitions are registry rows - a reference indexed for filtering only. Such a partition with one owner is a registry
 * row and nothing else, so generic index creation must not mint an object, and with it a second primary key, for it.
 * An index object that already exists - a partition with two owners - is still answered, and the partitions of a
 * reference indexed for partitioning are created as always.
 *
 * The catalog is a new one, so it keeps its single-owner partitions as registry rows from the first write on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Refused creation of partition objects of registry rows")
@Tag(ENGINE)
@Tag(REFERENCE)
class VirtualPartitionObjectCreationTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualPartitionObjectCreationTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	/** A reference indexed for filtering - its single-owner partitions are registry rows. */
	private static final String REF_CATEGORIES = "categories";
	/** A reference indexed for partitioning - its partitions are always index objects. */
	private static final String REF_PARTITIONED_CATEGORIES = "partitionedCategories";
	/** The category both products reference - its partition has two owners and is an index object. */
	private static final int SHARED_CATEGORY_PK = 1;
	/** The category only {@link #PRODUCT_PK} references - its partition is a registry row. */
	private static final int SINGLE_OWNER_CATEGORY_PK = 2;
	/** A category no product references. */
	private static final int UNREFERENCED_CATEGORY_PK = 3;
	/** The product referencing both {@link #SHARED_CATEGORY_PK} and {@link #SINGLE_OWNER_CATEGORY_PK}. */
	private static final int PRODUCT_PK = 100;
	/** The product referencing {@link #SHARED_CATEGORY_PK} only. */
	private static final int OTHER_PRODUCT_PK = 101;

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

	/**
	 * Asserts that the collection refuses to create the index object of a partition of {@link #REF_CATEGORIES} and
	 * that no object of it exists afterwards.
	 *
	 * @param products the product collection
	 * @param key      the key of the partition
	 */
	private static void assertCreationRefused(@Nonnull EntityCollection products, @Nonnull EntityIndexKey key) {
		final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products.getEntityIndexMaintainer();
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class, () -> maintainer.getOrCreateIndex(key)
		);
		assertTrue(error.getPrivateMessage().contains("must not be created"), error.getPrivateMessage());
		assertTrue(error.getPrivateMessage().contains(REF_CATEGORIES), error.getPrivateMessage());
		assertNull(products.getIndexByKeyIfExists(key), "the refused index must not exist");
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualPartitionObjectCreationTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
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
				session.createNewEntity(ENTITY_CATEGORY, SHARED_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, SINGLE_OWNER_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, UNREFERENCED_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
					.setReference(REF_CATEGORIES, SHARED_CATEGORY_PK)
					.setReference(REF_CATEGORIES, SINGLE_OWNER_CATEGORY_PK)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, OTHER_PRODUCT_PK)
					.setReference(REF_CATEGORIES, SHARED_CATEGORY_PK)
					.upsertVia(session);
			}
		);
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

	@Test
	@DisplayName("the existing index object of a partition with two owners is answered")
	void shouldAnswerExistingPartitionObject() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				final EntityCollection products = products();
				final EntityIndexKey key = partitionKey(REF_CATEGORIES, SHARED_CATEGORY_PK);
				final EntityIndex existing = products.getIndexByKeyIfExists(key);
				assertNotNull(existing, "self-check: the partition with two owners must be an index object");
				assertSame(existing, products.getEntityIndexMaintainer().getOrCreateIndex(key));
			}
		);
	}

	@Test
	@DisplayName("the index object of a single-owner partition, which is a registry row, is not created")
	void shouldRefuseCreatingObjectOfSingleOwnerPartition() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				final EntityCollection products = products();
				final RegisteredRow row = VirtualPartitionInvariants.registeredRowsOf(products)
					.values()
					.stream()
					.filter(
						it -> it.scope() == Scope.LIVE && REF_CATEGORIES.equals(it.referenceName()) &&
							it.target() == SINGLE_OWNER_CATEGORY_PK
					)
					.findFirst()
					.orElseThrow(() -> new AssertionError("self-check: the single-owner partition must be a row"));
				assertEquals(PRODUCT_PK, row.owner(), "self-check: the row must name its only owner");
				assertCreationRefused(products, partitionKey(REF_CATEGORIES, SINGLE_OWNER_CATEGORY_PK));
			}
		);
	}

	@Test
	@DisplayName("the index object of a partition no product owns is not created")
	void shouldRefuseCreatingObjectOfUnreferencedPartition() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				assertCreationRefused(products(), partitionKey(REF_CATEGORIES, UNREFERENCED_CATEGORY_PK));
			}
		);
	}

	@Test
	@DisplayName("the index object of a partition of a reference indexed for partitioning is created as always")
	void shouldCreatePartitionObjectOfPartitioningReference() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				final EntityCollection products = products();
				final EntityIndexKey key = partitionKey(REF_PARTITIONED_CATEGORIES, UNREFERENCED_CATEGORY_PK);
				assertInstanceOf(ReducedEntityIndex.class, products.getEntityIndexMaintainer().getOrCreateIndex(key));
				assertNotNull(products.getIndexByKeyIfExists(key));
			}
		);
	}

}
