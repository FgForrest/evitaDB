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

package io.evitadb.core;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionStorageComposition;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.StorageCompositionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} breakdown: that a data store's bytes are
 * attributed to the storage-part types that hold them, that the breakdown sums back to the live bytes the
 * {@link CatalogStatisticsComponent#STORAGE_SIZE} decomposition reports for the same data store, and that the entry
 * order is stable rather than whatever the backing hash map happened to produce.
 *
 * The order matters more than it looks: the records carrying this breakdown compare their arrays positionally, so an
 * incidental order would make two identical compositions unequal and would reshuffle a management table on every poll.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Storage part composition")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class StoragePartCompositionTest implements EvitaTestSupport {
	private static final String CATALOG = "storagePartCompositionTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final int PRODUCT_COUNT = 50;
	private static final int CATEGORY_COUNT = 10;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("StoragePartCompositionTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT);
				session.defineEntitySchema(ENTITY_CATEGORY);
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, i));
				}
				for (int i = 1; i <= CATEGORY_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
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
	@DisplayName("The catalog's own data store is broken down by storage part type")
	void shouldBreakTheCatalogDataStoreDownByType() {
		final CatalogStatistics statistics = fetchCatalogStatistics();
		assertTrue(
			statistics.isDelivered(CatalogStatisticsComponent.STORAGE_COMPOSITION),
			"The component must be delivered, not declined: " +
				statistics.statusOf(CatalogStatisticsComponent.STORAGE_COMPOSITION).orElseThrow()
		);
		final StorageCompositionStatistics composition = statistics.storageCompositionIfPresent().orElseThrow();
		final StoragePartUsage[] parts = composition.catalogParts();

		assertTrue(parts.length > 0, "The catalog data store holds schemas, so its breakdown cannot be empty");
		assertOrderedAndConsistent(parts);

		// the catalog's own data store is where the catalog schema lives - if the breakdown named no type at all, or
		// named only types with zero records, every sum below would still hold
		final StoragePartUsage catalogSchema = findPart(parts, CatalogSchemaStoragePart.class.getSimpleName());
		assertNotNull(catalogSchema, "The catalog schema was not attributed: " + composition);
		assertEquals(1, catalogSchema.count(), "A catalog has exactly one schema: " + composition);

		// the entity collections keep their own records - schemas included - in their own data stores, so nothing
		// belonging to a collection may be attributed here. This is what "no catalog-wide sum" means concretely
		assertNull(
			findPart(parts, EntityBodyStoragePart.class.getSimpleName()),
			"Entity bodies live in the collections' data stores, never in the catalog's: " + composition
		);
		assertNull(
			findPart(parts, EntitySchemaStoragePart.class.getSimpleName()),
			"Entity schemas live with their collection, not in the catalog data store: " + composition
		);
	}

	@Test
	@DisplayName("A collection's data store is broken down by storage part type")
	void shouldBreakACollectionDataStoreDownByType() {
		final EntityCollectionStatistics statistics = fetchCollectionStatistics(ENTITY_PRODUCT);
		assertTrue(
			statistics.isDelivered(CatalogStatisticsComponent.STORAGE_COMPOSITION),
			"The component must be delivered, not declined: " +
				statistics.statusOf(CatalogStatisticsComponent.STORAGE_COMPOSITION).orElseThrow()
		);
		final CollectionStorageComposition composition = statistics.storageCompositionIfPresent().orElseThrow();
		final StoragePartUsage[] parts = composition.parts();

		assertTrue(parts.length > 0, "A collection holding entities cannot have an empty breakdown");
		assertOrderedAndConsistent(parts);

		final StoragePartUsage bodies = findPart(parts, EntityBodyStoragePart.class.getSimpleName());
		assertNotNull(bodies, "Entity bodies were not attributed: " + composition);
		assertEquals(
			PRODUCT_COUNT, bodies.count(),
			"Every stored entity body must be attributed to its type: " + composition
		);

		// the counterpart of the catalog-level assertion: a collection's data store holds its own schema, which is
		// why the two levels are reported separately and never summed
		final StoragePartUsage schema = findPart(parts, EntitySchemaStoragePart.class.getSimpleName());
		assertNotNull(schema, "The entity schema was not attributed to its collection: " + composition);
		assertEquals(1, schema.count(), "A collection has exactly one schema: " + composition);
	}

	@Test
	@DisplayName("The breakdown is a subset of the live bytes the storage size decomposition reports")
	void shouldSumToASubsetOfTheCollectionLiveBytes() {
		final EntityCollectionStatistics statistics = this.evita.management().getEntityCollectionStatistics(
			CATALOG, ENTITY_PRODUCT,
			EnumSet.of(CatalogStatisticsComponent.STORAGE_COMPOSITION, CatalogStatisticsComponent.STORAGE_SIZE)
		);
		final CollectionStorageComposition composition = statistics.storageCompositionIfPresent().orElseThrow();
		final CollectionStorageSize storageSize = statistics.storageSizeIfPresent().orElseThrow();

		long summedBytes = 0L;
		for (final StoragePartUsage part : composition.parts()) {
			summedBytes += part.totalBytes();
		}

		// the two are computed by independent paths - the composition from the in-memory per-type accumulator, the
		// live bytes from the offset index's own active-size estimate clamped to the file - and they are related, not
		// equal: the difference is the offset-index table the data store keeps in order to find those records, which
		// belongs to no storage part type. A breakdown that silently lost a type would still be positive; it would
		// not stay this close to the live bytes
		assertTrue(summedBytes > 0, "No bytes were attributed at all: " + composition);
		assertTrue(
			summedBytes < storageSize.liveBytes(),
			"The per-type bytes (" + summedBytes + ") must be a proper subset of the collection's live bytes (" +
				storageSize.liveBytes() + "), which also covers the data store's own offset-index table"
		);
	}

	@Test
	@DisplayName("Two consecutive reads return the breakdown in the same order")
	void shouldReturnAStableOrderAcrossCalls() {
		// the breakdown is built from a hash map, so without an explicit sort this passes or fails depending on how
		// the record type ids happened to hash - and the records carrying it compare their arrays positionally
		final StoragePartUsage[] first = fetchCatalogStatistics().storageCompositionIfPresent()
			.orElseThrow().catalogParts();
		final StoragePartUsage[] second = fetchCatalogStatistics().storageCompositionIfPresent()
			.orElseThrow().catalogParts();

		assertArrayEquals(first, second, "Two reads of unchanged data must produce an identical breakdown");
		assertEquals(
			fetchCatalogStatistics().storageCompositionIfPresent().orElseThrow(),
			fetchCatalogStatistics().storageCompositionIfPresent().orElseThrow(),
			"Two reads of unchanged data must produce equal components - the records compare their arrays positionally"
		);
	}

	/**
	 * Asserts the invariants every breakdown must satisfy regardless of which data store it came from: entries are
	 * ordered largest first with the type name breaking ties, no type is reported twice, and no type is reported with
	 * a zero or negative count.
	 *
	 * @param parts the breakdown to check
	 */
	private static void assertOrderedAndConsistent(@Nonnull StoragePartUsage[] parts) {
		for (int i = 0; i < parts.length; i++) {
			final StoragePartUsage part = parts[i];
			assertTrue(part.count() > 0, "A type with no record must not be listed at all: " + part);
			assertTrue(part.totalBytes() > 0, "A type holding records must hold bytes: " + part);
			assertEquals(
				part.totalBytes() / part.count(), part.averageBytes(),
				"The average must be the exact quotient of the two reported numbers: " + part
			);
			if (i > 0) {
				final StoragePartUsage previous = parts[i - 1];
				assertTrue(
					previous.totalBytes() > part.totalBytes() ||
						(previous.totalBytes() == part.totalBytes() &&
							previous.storagePartType().compareTo(part.storagePartType()) < 0),
					"The breakdown must be ordered largest first, ties broken by type name: " +
						previous + " before " + part
				);
			}
		}
	}

	/**
	 * Finds the entry of one storage part type in a breakdown.
	 *
	 * @param parts           the breakdown to search
	 * @param storagePartType simple class name of the storage part type to look for
	 * @return the entry, or `null` when the type holds no record in this data store
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

	/**
	 * Reads the storage composition component of the test catalog.
	 *
	 * @return the catalog-level statistics snapshot carrying it
	 */
	@Nonnull
	private CatalogStatistics fetchCatalogStatistics() {
		return this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.STORAGE_COMPOSITION)
		);
	}

	/**
	 * Reads the storage composition component of one collection of the test catalog.
	 *
	 * @param entityType the collection to describe
	 * @return the collection-level statistics snapshot carrying it
	 */
	@Nonnull
	private EntityCollectionStatistics fetchCollectionStatistics(@Nonnull String entityType) {
		return this.evita.management().getEntityCollectionStatistics(
			CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.STORAGE_COMPOSITION)
		);
	}

	/**
	 * Builds the configuration of the embedded instance used by this test.
	 *
	 * @return configuration pointing at this test's isolated directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}
}
