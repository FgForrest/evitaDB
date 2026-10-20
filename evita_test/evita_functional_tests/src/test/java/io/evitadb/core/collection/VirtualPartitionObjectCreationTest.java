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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the index objects a collection writes into next to the registry rows of a reference indexed for filtering
 * only: a write into a partition with two owners reaches its existing index object, and the partitions of a reference
 * indexed for partitioning are created as index objects as always.
 *
 * The collection also refuses to create the index object of a partition whose single-owner partitions are registry
 * rows, so that generic index creation never mints an object, and with it a second primary key, for a row. No write a
 * client can make asks for such an object - a partition gaining its second owner receives its object under the primary
 * key of its row instead - so the refusal is a premise of the write path and has no case here.
 *
 * The catalog is a new one, so it keeps its single-owner partitions as registry rows from the first write on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Creation of partition objects next to registry rows")
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
	/** The product referencing nothing at first. */
	private static final int UNREFERENCING_PRODUCT_PK = 102;

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
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						ReferenceSchemaEditor::indexedForFiltering
					)
					.withReferenceToEntity(
						REF_PARTITIONED_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
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
				session.createNewEntity(ENTITY_PRODUCT, UNREFERENCING_PRODUCT_PK).upsertVia(session);
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

	/**
	 * Lets the product referencing nothing join a category through the given reference.
	 *
	 * @param referenceName the reference
	 * @param categoryPk    the category
	 */
	private void joinCategory(@Nonnull String referenceName, int categoryPk) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getEntity(ENTITY_PRODUCT, UNREFERENCING_PRODUCT_PK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(referenceName, categoryPk)
					.upsertVia(session);
			}
		);
	}

	@Test
	@DisplayName("a write into a partition with two owners reaches its existing index object")
	void shouldWriteIntoExistingPartitionObject() {
		final EntityIndexKey key = partitionKey(REF_CATEGORIES, SHARED_CATEGORY_PK);
		final EntityIndex existing = products().getIndexByKeyIfExists(key);
		assertNotNull(existing, "self-check: the partition with two owners must be an index object");

		joinCategory(REF_CATEGORIES, SHARED_CATEGORY_PK);

		final EntityIndex written = assertInstanceOf(ReducedEntityIndex.class, products().getIndexByKeyIfExists(key));
		assertEquals(
			existing.getPrimaryKey(), written.getPrimaryKey(),
			"The write must reach the existing index object, not one created under another primary key."
		);
		assertArrayEquals(
			new int[]{PRODUCT_PK, OTHER_PRODUCT_PK, UNREFERENCING_PRODUCT_PK}, written.getAllPrimaryKeys().getArray()
		);
	}

	@Test
	@DisplayName("the index object of a partition of a reference indexed for partitioning is created as always")
	void shouldCreatePartitionObjectOfPartitioningReference() {
		final EntityIndexKey key = partitionKey(REF_PARTITIONED_CATEGORIES, UNREFERENCED_CATEGORY_PK);
		assertNull(products().getIndexByKeyIfExists(key), "self-check: no product references the category yet");

		joinCategory(REF_PARTITIONED_CATEGORIES, UNREFERENCED_CATEGORY_PK);

		final EntityIndex created = assertInstanceOf(ReducedEntityIndex.class, products().getIndexByKeyIfExists(key));
		assertArrayEquals(new int[]{UNREFERENCING_PRODUCT_PK}, created.getAllPrimaryKeys().getArray());
	}

}
