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
import io.evitadb.api.statistics.StoragePartGroup;
import io.evitadb.api.statistics.StoragePartKind;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.TreeMap;

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
	private static final String GLOBAL_ATTRIBUTE = "globalCode";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("StoragePartCompositionTest");
		this.evita = new Evita(getEvitaConfiguration());
		// the globally unique attribute is what puts an *index* into the catalog's own data store - without it that
		// store holds schemas and headers only, and every claim about how an index is classified there is untestable
		this.evita.defineCatalog(CATALOG)
			.withAttribute(GLOBAL_ATTRIBUTE, String.class, thatIs -> thatIs.uniqueGlobally())
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withGlobalAttribute(GLOBAL_ATTRIBUTE)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY);
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i).setAttribute(GLOBAL_ATTRIBUTE, "product-" + i)
					);
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
	@DisplayName("Every entry names the kind of data it holds, and the schema is metadata rather than entity data")
	void shouldClassifyEveryEntryOfACollectionBreakdown() {
		final CollectionStorageComposition composition = fetchCollectionStatistics(ENTITY_PRODUCT)
			.storageCompositionIfPresent().orElseThrow();

		// `kind()` is derived from `group()`, so asserting the two agree would hold whatever the engine reported, and
		// so would asserting the group is present at all - the registry returns one or raises. What can actually be
		// wrong is which group, which is what the per-type assertions below check
		final StoragePartUsage bodies = findPart(composition.parts(), EntityBodyStoragePart.class.getSimpleName());
		assertNotNull(bodies);
		assertEquals(StoragePartGroup.ENTITY_BODY, bodies.group(), "An entity body is entity data: " + bodies);
		assertEquals(StoragePartKind.ENTITY_DATA, bodies.kind());

		// the entity schema is declared by the *entity* storage part registry yet is metadata - which is why the
		// classification is declared per type rather than inferred from the registry that declares it
		final StoragePartUsage schema = findPart(composition.parts(), EntitySchemaStoragePart.class.getSimpleName());
		assertNotNull(schema);
		assertEquals(StoragePartGroup.SCHEMA, schema.group(), "An entity schema is metadata, not entity data: " + schema);
		assertEquals(StoragePartKind.METADATA, schema.kind());
	}

	@Test
	@DisplayName("The catalog's own data store holds metadata and catalog-level indexes, and no entity data")
	void shouldClassifyTheCatalogStoreAsMetadataAndIndexesOnly() {
		final StorageCompositionStatistics composition = fetchCatalogStatistics()
			.storageCompositionIfPresent().orElseThrow();

		// this is the concrete reason a two-way data/index split cannot work: the catalog's own store is entirely
		// metadata and catalog-level indexes, so folding metadata into "entity data" would render a schema as data
		final EnumSet<StoragePartKind> kindsHeld = EnumSet.noneOf(StoragePartKind.class);
		for (final StoragePartUsage part : composition.catalogParts()) {
			kindsHeld.add(part.kind());
		}
		assertEquals(
			EnumSet.of(StoragePartKind.METADATA, StoragePartKind.INDEX), kindsHeld,
			"The catalog's own store holds its metadata and the catalog-level indexes, and nothing else: " + composition
		);

		final StoragePartUsage catalogSchema = findPart(
			composition.catalogParts(), CatalogSchemaStoragePart.class.getSimpleName()
		);
		assertNotNull(catalogSchema, "The catalog schema was not attributed: " + composition);
		assertEquals(StoragePartGroup.SCHEMA, catalogSchema.group());

		// the end-to-end proof that a group is not implied by the store a part lives in, nor by the registry that
		// declares it: this is an *attribute index* row, sitting in a store that holds no attribute value at all
		final StoragePartUsage globalUnique = findPart(
			composition.catalogParts(), GlobalUniqueIndexStoragePart.class.getSimpleName()
		);
		assertNotNull(
			globalUnique,
			"A globally unique attribute puts its index into the catalog's own data store: " + composition
		);
		assertEquals(
			StoragePartGroup.ATTRIBUTE_INDEX, globalUnique.group(),
			"A globally unique index is what an attribute's `uniqueGlobally` flag costs: " + globalUnique
		);
		assertEquals(StoragePartKind.INDEX, globalUnique.kind());
	}

	@Test
	@DisplayName("Folding by group and by kind conserves every byte")
	void shouldConserveBytesAcrossBothFolds() {
		// the only arithmetic a composition table has to do: a client folds the rows by group and then folds the
		// groups by kind, and every byte has to arrive in exactly one bucket of each fold
		final CollectionStorageComposition composition = fetchCollectionStatistics(ENTITY_PRODUCT)
			.storageCompositionIfPresent().orElseThrow();

		final Map<String, Long> byType = new TreeMap<>();
		final Map<StoragePartGroup, Long> byGroup = new EnumMap<>(StoragePartGroup.class);
		final Map<StoragePartKind, Long> byKind = new EnumMap<>(StoragePartKind.class);
		for (final StoragePartUsage part : composition.parts()) {
			byType.merge(part.storagePartType(), part.totalBytes(), Long::sum);
			byGroup.merge(part.group(), part.totalBytes(), Long::sum);
			byKind.merge(part.kind(), part.totalBytes(), Long::sum);
		}

		// every equality below has one side summed over rows picked by *type name*, an axis the classification has no
		// say in. Summing the same rows re-bucketed against themselves would be an arithmetic identity that holds for
		// any classification whatsoever, right or wrong
		final long entityBodyBytes = bytesOf(byType, EntityBodyStoragePart.class);
		final long attributeBytes = bytesOf(byType, AttributesStoragePart.class);
		assertTrue(entityBodyBytes > 0, "A collection holding entities must attribute bytes: " + composition);
		assertTrue(attributeBytes > 0, "The products carry a globally unique value, so they carry attributes");

		assertTrue(
			byGroup.keySet().containsAll(
				EnumSet.of(
					StoragePartGroup.ENTITY_BODY, StoragePartGroup.ATTRIBUTE_DATA,
					StoragePartGroup.SCHEMA, StoragePartGroup.INDEX_MANIFEST
				)
			),
			"A collection of stored entities holds its bodies, their attributes, its schema and its index: " + byGroup
		);
		assertEquals(
			entityBodyBytes, byGroup.getOrDefault(StoragePartGroup.ENTITY_BODY, 0L),
			"The entity body group must hold exactly the bytes of the entity body rows: " + byGroup
		);
		assertEquals(
			attributeBytes, byGroup.getOrDefault(StoragePartGroup.ATTRIBUTE_DATA, 0L),
			"The attribute data group must hold exactly the bytes of the attribute rows: " + byGroup
		);

		// the fold to a kind: this collection's only entity-data types are the body and its attributes and its only
		// metadata is its schema, so a group folding to the wrong kind - in either direction - parts the two sides,
		// and the remainder pins the third kind without naming the index types the engine happens to have written
		final long schemaBytes = bytesOf(byType, EntitySchemaStoragePart.class);
		final long attributedBytes = byType.values().stream().mapToLong(Long::longValue).sum();
		assertEquals(
			entityBodyBytes + attributeBytes, byKind.getOrDefault(StoragePartKind.ENTITY_DATA, 0L),
			"Entity data is the bodies and their attributes, and nothing else this collection holds: " + byKind
		);
		assertEquals(
			schemaBytes, byKind.getOrDefault(StoragePartKind.METADATA, 0L),
			"The only metadata a collection's data store holds is its own schema: " + byKind
		);
		assertEquals(
			attributedBytes - entityBodyBytes - attributeBytes - schemaBytes,
			byKind.getOrDefault(StoragePartKind.INDEX, 0L),
			"Everything a collection's data store holds beyond its entities and its schema is index: " + byKind
		);
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
	 * Bytes the breakdown attributes to one storage part type, looked up by the simple class name it is reported
	 * under - an axis independent of the classification, which is what lets a fold assertion actually fail.
	 *
	 * @param byType          bytes accumulated per reported type name
	 * @param storagePartType the storage part type to look up
	 * @return the bytes attributed to it, or `0` when the data store holds no record of it
	 */
	private static long bytesOf(@Nonnull Map<String, Long> byType, @Nonnull Class<?> storagePartType) {
		return byType.getOrDefault(storagePartType.getSimpleName(), 0L);
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
