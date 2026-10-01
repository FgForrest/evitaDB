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

package io.evitadb.index;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.core.Evita;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that carrying a {@link VirtualReferencePartitionIndex} on every referenced-type entity index changes nothing
 * on disk while no virtual partition is ever recorded: a catalog with filter-indexed references - one of them allowing
 * duplicates told apart by a representative attribute - persists no part of the new record types in warm-up or in a
 * transaction, and a catalog persisted without them loads and answers reference queries after a restart.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index persists nothing while unused")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(REFERENCE)
class VirtualReferencePartitionNeutralityTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionNeutralityTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_MEDIA = "media";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_GALLERY = "gallery";
	private static final String ATTRIBUTE_ROLE = "role";
	private static final int PRODUCT_COUNT = 40;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionNeutralityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
				session.defineEntitySchema(ENTITY_MEDIA).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
					)
					.withReferenceToEntity(
						REFERENCE_GALLERY, ENTITY_MEDIA, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute(ATTRIBUTE_ROLE, String.class, thatIs -> thatIs.filterable().representative())
					)
					.updateVia(session);
				for (int i = 1; i <= 5; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
					session.upsertEntity(session.createNewEntity(ENTITY_MEDIA, i));
				}
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.upsertEntity(newProduct(session, i));
				}
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a warm-up catalog flushes no part of the virtual reference partition index")
	void shouldPersistNoPartInWarmUp() {
		assertNoVirtualReferencePartitionParts();
	}

	@Test
	@DisplayName("a transactional catalog flushes no part of the virtual reference partition index")
	void shouldPersistNoPartInTransactions() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				for (int i = PRODUCT_COUNT + 1; i <= PRODUCT_COUNT + 10; i++) {
					session.upsertEntity(newProduct(session, i));
				}
				session.deleteEntity(ENTITY_PRODUCT, 1);
			}
		);
		assertNoVirtualReferencePartitionParts();
	}

	@Test
	@DisplayName("a catalog persisted without any root of the index loads and answers reference queries alike")
	void shouldLoadCatalogWithoutAnyRoot() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final List<List<EntityReferenceContract>> before = this.evita.queryCatalog(CATALOG, this::queryByReferences);
		assertFalse(before.get(0).isEmpty(), "The category query must match products");
		assertEquals(PRODUCT_COUNT, before.get(1).size(), "Every product has a thumbnail");

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();

		assertEquals(before, this.evita.queryCatalog(CATALOG, this::queryByReferences));
		assertNoVirtualReferencePartitionParts();
	}

	/**
	 * Runs one query through each filter-indexed reference - both are answered from the referenced-type entity
	 * indexes, which the loader has to have rebuilt.
	 *
	 * @param session the session to query in
	 * @return the products referencing category 1, then the products with a thumbnail
	 */
	@Nonnull
	private List<List<EntityReferenceContract>> queryByReferences(@Nonnull EvitaSessionContract session) {
		return List.of(
			session.queryListOfEntityReferences(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(1))),
					require(page(1, PRODUCT_COUNT))
				)
			),
			session.queryListOfEntityReferences(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(referenceHaving(REFERENCE_GALLERY, attributeEquals(ATTRIBUTE_ROLE, "thumbnail"))),
					require(page(1, PRODUCT_COUNT))
				)
			)
		);
	}

	/**
	 * Creates a product referencing one category and one media twice, under two roles.
	 *
	 * @param session    the session to create the entity in
	 * @param primaryKey the product primary key
	 * @return the entity builder
	 */
	@Nonnull
	private static EntityBuilder newProduct(
		@Nonnull EvitaSessionContract session, int primaryKey
	) {
		final int media = primaryKey % 5 + 1;
		return session
			.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setReference(REFERENCE_CATEGORIES, primaryKey % 5 + 1)
			.setOrUpdateReference(
				REFERENCE_GALLERY, media,
				ref -> "gallery".equals(ref.getAttribute(ATTRIBUTE_ROLE)),
				ref -> ref.setAttribute(ATTRIBUTE_ROLE, "gallery")
			)
			.setOrUpdateReference(
				REFERENCE_GALLERY, media,
				ref -> "thumbnail".equals(ref.getAttribute(ATTRIBUTE_ROLE)),
				ref -> ref.setAttribute(ATTRIBUTE_ROLE, "thumbnail")
			);
	}

	/**
	 * Asserts the product collection stores reference-type indexes but no part of the virtual reference partition
	 * index. The first half is what makes the second meaningful: the referenced-type entity indexes were flushed.
	 */
	private void assertNoVirtualReferencePartitionParts() {
		final StoragePartUsage[] parts = this.evita.management()
			.getEntityCollectionStatistics(
				CATALOG, ENTITY_PRODUCT, EnumSet.of(CatalogStatisticsComponent.STORAGE_COMPOSITION)
			)
			.storageCompositionIfPresent()
			.orElseThrow()
			.parts();
		final StoragePartUsage cardinalityRoots =
			findPart(parts, ReferenceTypeCardinalityIndexStoragePart.class.getSimpleName());
		assertNotNull(cardinalityRoots, "The referenced-type entity indexes must have been flushed");
		assertTrue(cardinalityRoots.count() >= 2, "Both references must have flushed their type index");
		assertNull(
			findPart(parts, VirtualReferencePartitionIndexStoragePart.class.getSimpleName()),
			"No virtual reference partition root may be persisted"
		);
		assertNull(
			findPart(parts, VirtualReferencePartitionIndexLeafPagePart.class.getSimpleName()),
			"No virtual reference partition leaf page may be persisted"
		);
	}

	/**
	 * Finds the entry of one storage part type in a breakdown.
	 *
	 * @param parts           the breakdown to search
	 * @param storagePartType simple class name of the storage part type to look for
	 * @return the entry, or `null` when the type holds no record
	 */
	@Nullable
	private static StoragePartUsage findPart(@Nonnull StoragePartUsage[] parts, @Nonnull String storagePartType) {
		for (final StoragePartUsage part : parts) {
			if (storagePartType.equals(part.storagePartType())) {
				return part;
			}
		}
		return null;
	}
}
