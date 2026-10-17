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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.mutation.local.VirtualReferencePartitionWriter;
import io.evitadb.index.virtual.CatalogDescriber;
import io.evitadb.index.virtual.CatalogReloadTestSupport;
import io.evitadb.index.virtual.SingleOwnerPartitionFixture;
import io.evitadb.index.virtual.SortValueAndChainFixture;
import io.evitadb.index.virtual.VirtualReferencePartitionRawRowOracle;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.store.catalog.LegacyOwnerSortImages.LegacySortIndex;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.catalog.Migration_2026_3_UniqueUpgradeRoundTripTest.Shape;
import io.evitadb.store.catalog.Migration_2026_3_UniqueUpgradeRoundTripTest.Spellings;
import io.evitadb.store.catalog.Migration_2026_3_UniqueUpgradeRoundTripTest.StoredUniqueIndex;
import io.evitadb.store.catalog.OfflineCatalogImages.StoredFile;
import io.evitadb.store.catalog.VirtualReferencePartitionConversion.CollectionConversion;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_ORDER;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.newProduct;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertInvariants;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertStoredParts;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertUnchanged;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.catalogOf;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.collectionOf;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.describeIndexes;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.registeredRowsOf;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.assertRewritten;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.assertStillAsWritten;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.writeLegacySortIndexes;
import static io.evitadb.store.catalog.OfflineCatalogImages.assertOnlyAppended;
import static io.evitadb.store.catalog.OfflineCatalogImages.catalogFolder;
import static io.evitadb.store.catalog.OfflineCatalogImages.filesHeldOpenIn;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.migrateOffline;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.storedFiles;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the storage protocol migration of a catalog written by evitaDB 2026.2 (storage protocol 6) to protocol 8 -
 * {@link Migration_2026_3#upgradeFromStorageProtocolVersion_6_to_8}, which re-keys the unique and cardinality indexes
 * and converts the single-owner reference partitions into registry rows through
 * {@link VirtualReferencePartitionConversion} - over catalogs built through the engine, with no binary fixture.
 *
 * # The input
 *
 * A catalog of 2026.2 represents every partition of a reference by an index object of its own. The engine of this
 * version writes such objects for a reference indexed for filtering and partitioning only, and keeps the objects such a
 * reference held when it is changed to filtering only - nothing converts them. So every case writes its catalog - the
 * **generated** one - with every reference to be converted indexed for filtering and partitioning, runs its history,
 * and then changes those references to filtering only, as its **twin** has had them from its birth: the generated
 * catalog then holds single-owner partitions of filtering-only references as index objects, the shape the migration
 * exists for. The engine is closed and the published state of the generated catalog is stamped with storage protocol
 * 6 in its catalog header and in its bootstrap record ({@link OfflineCatalogImages#stamp}); its next load runs the real
 * migration - through the engine, or through the persistence service the engine loads it with.
 *
 * What this input cannot show, and how the cases cover it:
 *
 * - its parts are written by this version, so the re-key finds nothing to rewrite - one case respells the owner unique
 *   index of the very collection whose partitions are converted into the form 2026.2 stored, so both steps write into
 *   one collection;
 * - its objects carry the content indexed only for partitioning - the prices, facets and entity attributes of the
 *   owner - so one case writes owners that have none of it, and asserts that their objects hold only what an object of
 *   a reference indexed for filtering holds;
 * - the twin holds other shared objects than the generated catalog - the generated ones carry the content indexed for
 *   partitioning - so the two are compared by their registry rows, advertisements, membership, the keys and owners of
 *   their index objects and every query line, never by the internals of a shared object;
 * - it depends on the index objects of a reference surviving its change to filtering only; a change of that rule needs
 *   another way to write the input;
 * - every write happens before the change of the indexing, so the image holds no registry row - the migration refuses
 *   one, which no release writes.
 *
 * # The oracle
 *
 * The migrated catalog must be indistinguishable from its twin (see above), keep every invariant of a catalog writing
 * its single-owner partitions as rows ({@link io.evitadb.index.virtual.VirtualPartitionInvariants}), agree with its
 * entity bodies ({@link VirtualReferencePartitionRawRowOracle}), and store what its bodies call for. Every stored part
 * of a converted index object - its manifest, its entity ids and every part its footprint names - is gone from the
 * published state, and every stored part of an index object the migration keeps is byte-identical.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — single-owner partitions of 2026.2 converted into registry rows (v6→v8)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(REFERENCE)
@SuppressWarnings("removal") // the migration is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_VirtualPartitionsTest implements EvitaTestSupport {
	/**
	 * The catalog written as 2026.2 would have left it and migrated.
	 */
	private static final String GENERATED_CATALOG = "generatedCatalog";
	/**
	 * The catalog with the same history, its references indexed for filtering from its birth - the twin.
	 */
	private static final String TWIN_CATALOG = "twinCatalog";
	/**
	 * The storage protocol of 2026.2 - the one the migration starts from.
	 */
	private static final int RELEASED_PROTOCOL = 6;
	/**
	 * Generous bound on a load of a catalog holding a few hundred entities.
	 */
	private static final int OPERATION_TIMEOUT_SECONDS = 120;
	/**
	 * A checkpoint interval that never elapses within a test, so the committed transactions stay in the write-ahead
	 * log.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * The product the history archives, moving its partitions to the archived scope.
	 */
	private static final int ARCHIVED_PRODUCT = 5;
	/**
	 * The product the history creates, entering two partitions of its own.
	 */
	private static final int NEW_PRODUCT = 9;
	/**
	 * The second category the new product of the history enters alone.
	 */
	private static final int NEW_PRODUCT_SECOND_CATEGORY = 12;
	/**
	 * The entity type of the owners of the cases with a fixture of their own.
	 */
	private static final String ENTITY_OWNER = "owner";
	/**
	 * The entity type the owners reference.
	 */
	private static final String ENTITY_TARGET = "target";
	/**
	 * The reference of the owners, indexed in both scopes.
	 */
	private static final String REFERENCE_TARGETS = "targets";
	/**
	 * The reflection of {@link #REFERENCE_TARGETS} on the targets, inheriting its indexing.
	 */
	private static final String REFERENCE_OWNERS = "owners";
	/**
	 * The sortable attribute of the reference.
	 */
	private static final String ATTRIBUTE_RANK = "rank";
	/**
	 * A localized attribute of the owners unique across locales - a standalone owner unique index of the collection
	 * whose partitions are converted. Named as the attribute of the precedent whose respelling the case reuses.
	 */
	private static final String ATTRIBUTE_URL = "url";
	/**
	 * A sort-only moment of the owners - in the global index, and in every partition object of a reference indexed for
	 * partitioning, which carries the attributes of its owners.
	 */
	private static final String ATTRIBUTE_MOMENT = "moment";
	/**
	 * A sort-only moment of the reference - in every partition object, and in every registry row.
	 */
	private static final String ATTRIBUTE_SINCE = "since";
	/**
	 * 09:00Z of the first day the moments use.
	 */
	private static final OffsetDateTime NINE_AT_UTC = OffsetDateTime.parse("2024-01-01T09:00:00Z");
	/**
	 * The target the first owners share.
	 */
	private static final int SHARED_TARGET = 1_000;
	/**
	 * How many owners the case of the inherited indexing has.
	 */
	private static final int REFLECTED_OWNER_COUNT = 12;
	/**
	 * How many of them reference the shared target too - their reflected partitions have two owners.
	 */
	private static final int SHARING_OWNER_COUNT = 3;
	/**
	 * How many times the case of the failed migration opens the image - more than once, because what a failed open
	 * leaves behind accumulates over the retries of an engine.
	 */
	private static final int FAILED_OPEN_ATTEMPTS = 3;
	/**
	 * The history every equivalence case runs on top of its fixture, each change in a session of its own: an owner
	 * archived (partitions in both scopes), a single-owner partition gaining a second owner and losing it again (a
	 * promotion and a demotion in the twin), a new owner entering two partitions alone, and an owner leaving its only
	 * partition (a primary key the sequence handed out and nothing uses any longer).
	 */
	private static final List<Consumer<EvitaSessionContract>> HISTORY = List.of(
		session -> session.archiveEntity(ENTITY_PRODUCT, ARCHIVED_PRODUCT),
		updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, 2, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 21))),
		session -> newProduct(session, NEW_PRODUCT)
			.setReference(
				REFERENCE_CATEGORIES, NEW_PRODUCT_SECOND_CATEGORY,
				ref -> ref.setAttribute(ATTRIBUTE_ORDER, NEW_PRODUCT_SECOND_CATEGORY)
			)
			.upsertVia(session),
		updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, 4)),
		updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 6, ref -> ref.setAttribute(ATTRIBUTE_ORDER, 36))),
		updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, 6))
	);

	/**
	 * The directories the test allocated, removed after it.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	/**
	 * The engine the test runs, closed after it.
	 */
	@Nullable private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		this.evita = null;
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * The fixtures of the cases converting rows of every kind of value.
	 */
	enum ValueFixture {
		/**
		 * A sortable attribute of every type a sort index normalizes in its own way, and two compounds.
		 */
		EVERY_SORT_TYPE(SortValueAndChainFixture::setUpTypesIndexedAs),
		/**
		 * Both chain types on a reference reflected with its attributes inherited.
		 */
		CHAINS(SortValueAndChainFixture::setUpChainsIndexedAs);

		/**
		 * Defines the schema with the given index type of the references to convert, and writes the data.
		 */
		private final BiConsumer<EvitaSessionContract, ReferenceIndexType> setUp;

		ValueFixture(@Nonnull BiConsumer<EvitaSessionContract, ReferenceIndexType> setUp) {
			this.setUp = setUp;
		}
	}

	/**
	 * Cases comparing the migrated catalog with its twin.
	 */
	@Nested
	@DisplayName("The migrated catalog equals its twin")
	class MigratedEqualsTwin {

		/**
		 * The broad case: every read route of the fixture, shared and moved partitions, the reflected `products`, both
		 * scopes, duplicates told apart by a representative attribute, both chain types and a history of promotions and
		 * demotions, migrated through the engine and compared with the twin, then reloaded.
		 *
		 * @param state the state both catalogs are in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("every single-owner partition becomes the row the twin wrote, and no part of its object is left")
		void shouldConvertEverySingleOwnerPartitionIntoTheRowTheTwinWrote(CatalogState state) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("twin_" + state.name()), null, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, state, SingleOwnerPartitionFixture::setUpWithEveryReadRouteIndexedAs, HISTORY
			);
			engine.close();
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			assertTrue(
				inventory.convertedCount(ENTITY_PRODUCT) > 0 && inventory.convertedCount(ENTITY_CATEGORY) > 0,
				"both the products and the reflecting categories must hold single-owner partition objects: " +
					inventory
			);
			assertTrue(inventory.hasSubsidiaryParts(), "the converted objects must store more than a manifest");
			assertFalse(inventory.kept().isEmpty(), "shared and partitioned objects must stay: " + inventory);
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita migrated = start(configuration);
			assertMigratedLikeTheTwin("after the migration", migrated);
			final SortedMap<String, String> indexes = describeIndexes(migrated, GENERATED_CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(migrated, GENERATED_CATALOG);
			migrated.close();
			assertConversionFootprint("after the publication", configuration, inventory);

			final Evita reloaded = start(configuration);
			assertUnchanged("after a reload", indexes, describeIndexes(reloaded, GENERATED_CATALOG));
			assertUnchanged(
				"after a reload: the query lines", queries,
				CatalogDescriber.describeQueriesOf(reloaded, GENERATED_CATALOG)
			);
			reloaded.close();
			assertConversionFootprint("after the reload", configuration, inventory);
		}

		/**
		 * A reflected reference inheriting its indexing declares none of it, so a migration classifying by the stored
		 * declaration would leave its single-owner partitions as index objects.
		 */
		@Test
		@DisplayName("a reflected reference is classified by the indexing it inherits, not by what it declares")
		void shouldClassifyAReflectedReferenceByTheIndexingItInherits() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("inheritedIndexing"), null, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.ALIVE,
				(session, indexType) -> setUpOwners(
					session, REFLECTED_OWNER_COUNT, SHARING_OWNER_COUNT, true, indexType, false
				),
				List.of()
			);
			// the reflected reference inherits what makes its partitions virtual, so its stored declaration alone does
			// not say so - the migration must bind it to the reference it reflects before it classifies anything
			final ReferenceSchemaContract boundOwners = catalogOf(engine, GENERATED_CATALOG)
				.getEntitySchema(ENTITY_TARGET).orElseThrow()
				.getReference(REFERENCE_OWNERS).orElseThrow();
			assertTrue(VirtualReferencePartitionWriter.isWrittenVirtually(boundOwners, Scope.ARCHIVED));
			engine.close();
			final ReferenceSchemaContract storedOwners = withOfflineCatalog(
				configuration, GENERATED_CATALOG,
				service -> storedSchemasOf(service).get(ENTITY_TARGET).getReference(REFERENCE_OWNERS).orElseThrow()
			);
			assertFalse(
				VirtualReferencePartitionWriter.isWrittenVirtually(storedOwners, Scope.ARCHIVED),
				"the stored declaration of the reflected reference must not classify its partitions by itself, or " +
					"the case does not prove that the migration binds the schema"
			);
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			assertEquals(
				REFLECTED_OWNER_COUNT - SHARING_OWNER_COUNT, inventory.convertedCount(ENTITY_TARGET),
				"every reflected partition with a single owner must be an index object to convert: " + inventory
			);
			assertTrue(
				inventory.converted().keySet().stream().anyMatch(
					part -> ENTITY_TARGET.equals(part.entityType()) && part.scope() == Scope.ARCHIVED
				),
				"the archived targets must hold reflected partitions - the scope the stored declaration misses"
			);
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita migrated = start(configuration);
			assertEquals(
				REFLECTED_OWNER_COUNT - SHARING_OWNER_COUNT,
				registeredRowsOf(collectionOf(migrated, GENERATED_CATALOG, ENTITY_TARGET)).size(),
				"every reflected partition with a single owner must have become a row"
			);
			assertMigratedLikeTheTwin("after the migration", migrated);
			migrated.close();
			assertConversionFootprint("after the publication", configuration, inventory);
		}

		/**
		 * Every sort type a sort index normalizes in its own way, the compounds and both chain types reach the rows.
		 *
		 * @param fixture the fixture of the values
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(ValueFixture.class)
		@DisplayName("every sort value, compound value and chain predecessor of a row is the one the twin wrote")
		void shouldConvertEveryKindOfRowValue(ValueFixture fixture) {
			final EvitaConfiguration configuration = configurationOf(
				allocatePaths("values_" + fixture.name()), null, null
			);
			final Evita engine = start(configuration);
			buildCatalogs(engine, CatalogState.ALIVE, fixture.setUp, List.of());
			engine.close();
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			assertTrue(inventory.convertedCount(ENTITY_PRODUCT) > 0, inventory.toString());
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita migrated = start(configuration);
			assertMigratedLikeTheTwin("after the migration", migrated);
			migrated.close();
			assertConversionFootprint("after the publication", configuration, inventory);
		}

		/**
		 * The objects of the other cases carry the content a reference indexed for partitioning adds - the prices,
		 * facets and entity attributes of the owner. A catalog of 2026.2 held objects of references indexed for
		 * filtering, which hold only the attributes of the reference, its compounds and the locales of the owner. The
		 * owners of this case have nothing else, so their objects hold exactly that - asserted on the stored
		 * manifests - and the conversion must remove them completely all the same.
		 *
		 * The conversion removes a part only when it is stored. Removing a part that is not stored is a no-op of the
		 * offset index (`OffsetIndex#doRemove` finds no location and records nothing), so leaving that check out would
		 * change nothing observable here; what is observable is a stored part the conversion fails to remove.
		 */
		@Test
		@DisplayName("an index object holding only what a reference indexed for filtering holds converts completely")
		void shouldConvertAnObjectHoldingOnlyTheContentOfFilteringIndexing() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("filteringFootprint"), null, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.ALIVE,
				(session, indexType) -> setUpOwners(
					session, REFLECTED_OWNER_COUNT, SHARING_OWNER_COUNT, false, indexType, false
				),
				List.of()
			);
			engine.close();
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			assertEquals(REFLECTED_OWNER_COUNT, inventory.convertedCount(ENTITY_OWNER), inventory.toString());
			assertTrue(inventory.hasSubsidiaryParts(), "the converted objects must store more than a manifest");
			withOfflineCatalog(
				configuration, GENERATED_CATALOG,
				service -> {
					final long catalogVersion = service.getLastCatalogVersion();
					for (final PartitionPart manifest : inventory.manifestsOfConverted()) {
						final EntityIndexStoragePart part = Objects.requireNonNull(
							collectionServiceOf(service, catalogVersion, manifest.entityType())
								.getStoragePartPersistenceService()
								.getStoragePart(catalogVersion, manifest.partPrimaryKey(), EntityIndexStoragePart.class)
						);
						assertTrue(part.getPriceIndexes().isEmpty(), "no price index: " + part.getPriceIndexes());
						assertTrue(part.getFacetIndexes().isEmpty(), "no facet index: " + part.getFacetIndexes());
						for (final AttributeIndexStorageKey key : part.getAttributeIndexes()) {
							assertNotNull(key.attribute().referenceName(), "only reference attributes: " + key);
						}
						assertFalse(part.getAttributeIndexes().isEmpty(), "the reference attribute must be indexed");
					}
					return Boolean.TRUE;
				}
			);
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);

			final Evita migrated = start(configuration);
			assertMigratedLikeTheTwin("after the migration", migrated);
			migrated.close();
			assertConversionFootprint("after the publication", configuration, inventory);
		}

		/**
		 * No release wrote a registry row, so a catalog of protocol 6 holding one is mislabelled - the migration
		 * refuses it rather than rebuilding or discarding the rows.
		 */
		@Test
		@DisplayName("a protocol-6 catalog holding a registry row is refused, and nothing is published")
		void shouldRefuseAReleasedCatalogHoldingARegistryRow() {
			final EvitaConfiguration configuration = writeReleasedImageHoldingARegistryRow("registryRow");
			final CatalogBootstrap stamped = lastBootstrap(configuration, GENERATED_CATALOG);

			final Evita refusing = new Evita(configuration);
			Migration_2026_3_VirtualPartitionsTest.this.evita = refusing;
			assertThrows(Exception.class, refusing::waitUntilFullyInitialized);
			assertEquals(CatalogState.CORRUPTED, refusing.getCatalogState(GENERATED_CATALOG).orElseThrow());
			final Exception failure = assertThrows(
				Exception.class,
				() -> refusing.queryCatalog(
					GENERATED_CATALOG,
					session -> {
						return session.getAllEntityTypes();
					}
				)
			);
			final GenericEvitaInternalError premise = findCause(failure, GenericEvitaInternalError.class);
			assertTrue(
				premise.getPrivateMessage().contains("holds a registry row"), premise.getPrivateMessage()
			);
			refusing.close();
			assertEquals(stamped, lastBootstrap(configuration, GENERATED_CATALOG), "nothing may be published");
		}

		/**
		 * The storage part services of the catalog data file a failing migration opened must be closed before the
		 * failure propagates - the constructor's own cleanup cannot reach them, and an engine retrying the activation
		 * of a catalog that fails to load would otherwise leave another set of read handles behind at every attempt.
		 * The migration of the mislabelled image fails in the conversion, after it read the catalog-level parts.
		 *
		 * Read from the file descriptors the platform lists for the process (`/proc/self/fd`); skipped where it lists
		 * none.
		 */
		@Test
		@DisplayName("a failed migration leaves no file of the catalog open, however often the open is repeated")
		void shouldLeaveNoFileOfTheCatalogOpenWhenTheMigrationFails() {
			final EvitaConfiguration configuration = writeReleasedImageHoldingARegistryRow("registryRowHandles");
			final Path folder = catalogFolder(configuration, GENERATED_CATALOG);
			final Set<String> heldBefore = filesHeldOpenIn(folder);
			assumeTrue(heldBefore != null, "the platform does not list the open file descriptors of the process");
			for (int attempt = 1; attempt <= FAILED_OPEN_ATTEMPTS; attempt++) {
				final GenericEvitaInternalError failure = assertThrows(
					GenericEvitaInternalError.class,
					() -> withOfflineCatalog(
						configuration, GENERATED_CATALOG, service -> fail("The migration of the image must fail.")
					)
				);
				assertTrue(
					failure.getPrivateMessage().contains("holds a registry row"), failure.getPrivateMessage()
				);
				final Set<String> held = Objects.requireNonNull(filesHeldOpenIn(folder));
				held.removeAll(heldBefore);
				assertTrue(
					held.isEmpty(),
					"attempt " + attempt + ": the failed migration left files of the catalog open: " + held
				);
			}
		}

		/**
		 * A successful migration opens a storage part service of the catalog data file for every protocol it reads the
		 * header in, and returns the last one only - the one the migration ran on reads hundreds of parts through
		 * read handles of its own. Every superseded service must be closed before the open returns, or its read
		 * handles of the catalog data file outlive the closed catalog. A second open of the migrated catalog, which
		 * migrates nothing, is the control.
		 *
		 * Read from the file descriptors the platform lists for the process (`/proc/self/fd`); skipped where it lists
		 * none. No garbage collection is requested: the cleaner of a collected file would close a leaked descriptor and
		 * hide the leak.
		 */
		@Test
		@DisplayName("a successful migration leaves no file of the catalog open once the catalog is closed")
		void shouldLeaveNoFileOfTheCatalogOpenAfterASuccessfulMigration() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("migratedHandles"), null, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.ALIVE,
				(session, indexType) -> setUpOwners(
					session, REFLECTED_OWNER_COUNT, SHARING_OWNER_COUNT, false, indexType, false
				),
				List.of()
			);
			engine.close();
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Path folder = catalogFolder(configuration, GENERATED_CATALOG);
			final Set<String> heldBefore = filesHeldOpenIn(folder);
			assumeTrue(heldBefore != null, "the platform does not list the open file descriptors of the process");

			// the open migrates the catalog, and the service it returns still reads it
			final int storedSchemas = withOfflineCatalog(
				configuration, GENERATED_CATALOG, service -> storedSchemasOf(service).size()
			);
			assertTrue(
				storedSchemas > 0,
				"the migrated catalog must read its schemas through the service the migration returned"
			);
			assertEquals(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				lastBootstrap(configuration, GENERATED_CATALOG).storageProtocolVersion(),
				"the open must have migrated the catalog"
			);
			final Set<String> heldAfterMigration = Objects.requireNonNull(filesHeldOpenIn(folder));
			heldAfterMigration.removeAll(heldBefore);
			assertTrue(
				heldAfterMigration.isEmpty(),
				"the successful migration left files of the catalog open: " + heldAfterMigration
			);

			final Set<String> heldBeforeReopen = Objects.requireNonNull(filesHeldOpenIn(folder));
			withOfflineCatalog(configuration, GENERATED_CATALOG, service -> Boolean.TRUE);
			final Set<String> heldAfterReopen = Objects.requireNonNull(filesHeldOpenIn(folder));
			heldAfterReopen.removeAll(heldBeforeReopen);
			assertTrue(
				heldAfterReopen.isEmpty(),
				"an open migrating nothing left files of the catalog open: " + heldAfterReopen
			);

			final Evita migrated = start(configuration);
			assertMigratedLikeTheTwin("after the migration", migrated);
			migrated.close();
		}
	}

	/**
	 * Cases about what the migration publishes, and when.
	 */
	@Nested
	@DisplayName("The migration publishes once, and a failure before it leaves the published catalog as it was")
	class Publication {

		/**
		 * The migration is run on the published state as the engine wrote it (protocol 8, the index objects of the
		 * former indexing still there) because a catalog stamped with protocol 6 cannot be opened without the open
		 * migrating it; the published state is therefore checked on the bytes - the bootstrap file and the last record
		 * unchanged, every data file holding its former bytes as a prefix, no file added or removed - and on what an
		 * engine loads from it. The rerun is the open of the catalog stamped with protocol 6.
		 */
		@Test
		@DisplayName("a failed publication only appends and leaves the catalog as it was; a rerun converts it")
		void shouldPublishNothingWhenThePublicationFails() {
			final TestPaths paths = allocatePaths("publicationFailure");
			// thresholds a compacting flush would act on: were the migration to compact a data store or retire a file
			// on its way, the files would show a new generation or a missing file
			final StorageOptions compacting = StorageOptions.builder()
				.storageDirectory(paths.storage())
				.workDirectory(paths.work())
				.fileSizeCompactionThresholdBytes(16_384)
				.minimalActiveRecordShare(0.95)
				.minCompactionIntervalMilliseconds(0)
				.timeTravelEnabled(false)
				.build();
			final EvitaConfiguration configuration = configurationOf(paths, compacting, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.WARMING_UP, SingleOwnerPartitionFixture::setUpWithReflectedProductsIndexedAs,
				HISTORY
			);
			final SortedMap<String, String> indexes = describeIndexes(engine, GENERATED_CATALOG);
			final SortedMap<String, String> queries = CatalogDescriber.describeQueriesOf(engine, GENERATED_CATALOG);
			engine.close();
			// the inventory opens the catalog, which lets the store settle what it owes on its own - with time travel
			// off it trims the bootstrap file to the published record - so the files are taken after it, and
			// everything that changes after it is the migration's doing
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			final Set<String> convertedCollections = inventory.collectionsWithConverted();
			assertEquals(Set.of(ENTITY_PRODUCT, ENTITY_CATEGORY), convertedCollections, inventory.toString());
			final Path folder = catalogFolder(configuration, GENERATED_CATALOG);
			final SortedMap<String, StoredFile> before = storedFiles(folder);
			final CatalogBootstrap publishedBefore = lastBootstrap(configuration, GENERATED_CATALOG);

			// the migration of the published state, driven with the engine stopped, fails at its publication
			final AtomicInteger publications = new AtomicInteger();
			final AtomicReference<CatalogHeader<LogFileRecordReference, CollectionFileReference>> handedOver =
				new AtomicReference<>();
			final Map<String, CollectionFileReference> published = new HashMap<>();
			assertThrows(
				InjectedFailure.class,
				() -> migrateOffline(
					configuration, GENERATED_CATALOG,
					header -> {
						publications.incrementAndGet();
						handedOver.set(header);
						throw new InjectedFailure();
					},
					published
				)
			);
			assertEquals(1, publications.get(), "the migration publishes once");
			for (final String entityType : convertedCollections) {
				// the file location is compared on its own - a collection file reference is equal to another of the
				// same file whatever location it names
				assertNotEquals(
					published.get(entityType).fileLocation(),
					handedOver.get().collectionFileIndex().get(entityType).fileLocation(),
					"the one publication must address the converted data store of `" + entityType + "`"
				);
			}
			assertOnlyAppended("after the failed migration", before, storedFiles(folder), true);
			assertEquals(
				publishedBefore, lastBootstrap(configuration, GENERATED_CATALOG),
				"nothing may be published by a migration that failed"
			);

			final Evita afterFailure = start(configuration);
			assertUnchanged("after the failed migration", indexes, describeIndexes(afterFailure, GENERATED_CATALOG));
			assertUnchanged(
				"after the failed migration: the query lines", queries,
				CatalogDescriber.describeQueriesOf(afterFailure, GENERATED_CATALOG)
			);
			afterFailure.close();

			// the rerun through the load path: an open of the catalog of protocol 6 migrates it
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final SortedMap<String, StoredFile> beforeRerun = storedFiles(folder);
			withOfflineCatalog(configuration, GENERATED_CATALOG, service -> Boolean.TRUE);
			assertEquals(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				lastBootstrap(configuration, GENERATED_CATALOG).storageProtocolVersion()
			);
			assertOnlyAppended("after the migration", beforeRerun, storedFiles(folder), false);
			assertCompactionWouldHaveRun(configuration, compacting, convertedCollections);

			final Evita restarted = start(configuration);
			assertMigratedLikeTheTwin("after the rerun", restarted);
			restarted.close();
			assertConversionFootprint("after the rerun", configuration, inventory);
		}

		/**
		 * The collection whose partitions are converted also holds raw spellings in its owner unique indexes, so the
		 * re-key and the conversion both write into one data store and the choice of the collection reference the
		 * migration publishes decides whether both are published.
		 */
		@Test
		@DisplayName("a collection both re-keyed and converted publishes both, also after a failed first attempt")
		void shouldRekeyAndConvertOneCollectionAndRetryAfterAFailure() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("rekeyAndConvert"), null, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.ALIVE,
				(session, indexType) -> setUpOwners(
					session, REFLECTED_OWNER_COUNT, SHARING_OWNER_COUNT, false, indexType, true
				),
				List.of()
			);
			engine.close();
			final Spellings spellings = new Spellings();
			for (int owner = 1; owner <= REFLECTED_OWNER_COUNT; owner++) {
				spellings.raw(ATTRIBUTE_URL, urlOf(owner));
			}
			respellOwnerUniqueIndex(configuration, spellings);
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			assertEquals(Set.of(ENTITY_OWNER), inventory.collectionsWithConverted(), inventory.toString());
			final Path folder = catalogFolder(configuration, GENERATED_CATALOG);
			final SortedMap<String, StoredFile> before = storedFiles(folder);
			final CatalogBootstrap publishedBefore = lastBootstrap(configuration, GENERATED_CATALOG);

			// the first attempt fails at its publication: neither the re-key nor the conversion may be published
			assertThrows(
				InjectedFailure.class,
				() -> migrateOffline(
					configuration, GENERATED_CATALOG,
					header -> {
						throw new InjectedFailure();
					},
					new HashMap<>()
				)
			);
			assertOnlyAppended("after the failed migration", before, storedFiles(folder), true);
			assertEquals(publishedBefore, lastBootstrap(configuration, GENERATED_CATALOG));
			assertOwnerUrlsStored("after the failed migration", configuration, false);
			assertEquals(
				inventory.converted().keySet(),
				inventoryPartitions(configuration, GENERATED_CATALOG).converted().keySet(),
				"after the failed migration every object to convert must still be stored and listed"
			);

			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Evita migrated = start(configuration);
			assertMigratedLikeTheTwin("after the migration", migrated);
			migrated.close();
			assertOwnerUrlsStored("after the migration", configuration, true);
			// the re-key rewrites the unique index of the URLs the shared object holds, so only the converted objects
			// are checked here - the other cases check the kept objects byte for byte
			assertConversionFootprint("after the migration", configuration, inventory, false);

			final Evita reloaded = start(configuration);
			assertMigratedLikeTheTwin("after a reload", reloaded);
			reloaded.close();
			assertOwnerUrlsStored("after a reload", configuration, true);
		}

		/**
		 * Case (iv): the collection of the combined case above also holds owner sort indexes storing temporal values
		 * raw, as every release up to 2026.2 wrote them - the moment of the owners in the global index and in every
		 * partition object, and the moment of the reference in every partition object. So one collection is rewritten,
		 * re-keyed and converted: the sort indexes of the single-owner partition objects are rewritten and then removed
		 * with the objects the conversion turns into rows, those of the global index and of the shared object are
		 * rewritten and kept. The migration must publish all three, and a first attempt failing at its publication
		 * must leave the published raw state exactly as it was.
		 *
		 * The rows the conversion writes carry normalized sort values whatever the objects store: the registry
		 * normalizes every value it receives. What the order of the steps decides is that the rewrite is published -
		 * it writes into the data store before the conversion's final flush, which the publication addresses.
		 */
		@Test
		@DisplayName("a collection rewritten, re-keyed and converted publishes all three, also after a failed attempt")
		void shouldRewriteRekeyAndConvertOneCollectionAndRetryAfterAFailure() {
			final EvitaConfiguration configuration = configurationOf(
				allocatePaths("rewriteRekeyAndConvert"), null, null
			);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.ALIVE,
				(session, indexType) -> setUpOwners(
					session, REFLECTED_OWNER_COUNT, SHARING_OWNER_COUNT, false, indexType, true, true
				),
				List.of()
			);
			engine.close();
			final Spellings spellings = new Spellings();
			for (int owner = 1; owner <= REFLECTED_OWNER_COUNT; owner++) {
				spellings.raw(ATTRIBUTE_URL, urlOf(owner));
			}
			respellOwnerUniqueIndex(configuration, spellings);
			final List<LegacySortIndex> legacy = writeLegacySortIndexes(
				configuration, GENERATED_CATALOG, ENTITY_OWNER, Set.of(ATTRIBUTE_MOMENT, ATTRIBUTE_SINCE),
				Migration_2026_3_VirtualPartitionsTest::momentOf
			);
			final PartitionInventory inventory = inventoryPartitions(configuration, GENERATED_CATALOG);
			assertEquals(Set.of(ENTITY_OWNER), inventory.collectionsWithConverted(), inventory.toString());
			final Set<Long> legacyRoots = new HashSet<>();
			for (final LegacySortIndex legacyIndex : legacy) {
				legacyRoots.add(legacyIndex.rootPrimaryKey());
			}
			// the raw parts must reach every kind of entity index the rewrite covers, or the case proves less
			assertTrue(
				legacy.stream().anyMatch(it -> it.entityIndexKey().type() == EntityIndexType.GLOBAL),
				"the global index must hold a raw sort index: " + legacy
			);
			assertTrue(
				inventory.converted().keySet().stream().anyMatch(
					it -> it.type() == SortIndexStoragePart.class && legacyRoots.contains(it.partPrimaryKey())
				),
				"a single-owner partition object must hold a raw sort index: " + legacy
			);
			assertTrue(
				inventory.kept().keySet().stream().anyMatch(
					it -> it.type() == SortIndexStoragePart.class && legacyRoots.contains(it.partPrimaryKey())
				),
				"the shared partition object must hold a raw sort index: " + legacy
			);
			final Path folder = catalogFolder(configuration, GENERATED_CATALOG);
			final SortedMap<String, StoredFile> before = storedFiles(folder);
			final CatalogBootstrap publishedBefore = lastBootstrap(configuration, GENERATED_CATALOG);

			// the first attempt fails at its publication: neither step may be published
			assertThrows(
				InjectedFailure.class,
				() -> migrateOffline(
					configuration, GENERATED_CATALOG,
					header -> {
						throw new InjectedFailure();
					},
					new HashMap<>()
				)
			);
			assertOnlyAppended("after the failed migration", before, storedFiles(folder), true);
			assertEquals(publishedBefore, lastBootstrap(configuration, GENERATED_CATALOG));
			assertOwnerUrlsStored("after the failed migration", configuration, false);
			assertStillAsWritten("after the failed migration", configuration, GENERATED_CATALOG, ENTITY_OWNER, legacy);
			assertEquals(
				inventory.converted().keySet(),
				inventoryPartitions(configuration, GENERATED_CATALOG).converted().keySet(),
				"after the failed migration every object to convert must still be stored and listed"
			);

			// the rerun from the same published state, through the load path
			stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Evita migrated = start(configuration);
			assertMigratedLikeTheTwin("after the migration", migrated);
			migrated.close();
			assertOwnerUrlsStored("after the migration", configuration, true);
			assertTrue(
				assertRewritten("after the migration", configuration, GENERATED_CATALOG, ENTITY_OWNER, legacy) >= 2,
				"the sort indexes of the global index and of the shared object must have been rewritten and kept"
			);
			// every part of a kept object is byte-identical but the ones another step rewrites on purpose: the owner
			// unique part of the URLs the re-key rewrites, and the raw sort parts, checked above by what they hold
			assertConversionFootprint(
				"after the migration", configuration, inventory,
				part -> part.type() == UniqueIndexStoragePart.class ||
					(part.type() == SortIndexStoragePart.class && legacyRoots.contains(part.partPrimaryKey()))
			);

			final Evita reloaded = start(configuration);
			assertMigratedLikeTheTwin("after a reload", reloaded);
			reloaded.close();
			assertOwnerUrlsStored("after a reload", configuration, true);
			assertRewritten("after a reload", configuration, GENERATED_CATALOG, ENTITY_OWNER, legacy);
		}

		/**
		 * The published state is migrated before the write-ahead log tail - written over the objects of the former
		 * indexing - is replayed onto it.
		 */
		@Test
		@Tag(WAL)
		@DisplayName("the write-ahead log tail of a migrated catalog replays onto it as the twin replays its own")
		void shouldReplayTheWriteAheadLogTailOntoTheMigratedCatalog() {
			final TestPaths paths = allocatePaths("walTail");
			final TransactionOptions deferred = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			final Evita engine = start(configurationOf(paths, null, deferred));
			// the change of the indexing is part of the fixture, so it is published by the clean close below and the
			// history - written over the objects the former indexing left - is the tail
			buildCatalogs(
				engine, CatalogState.ALIVE, SingleOwnerPartitionFixture::setUpWithReflectedProductsIndexedAs,
				List.of()
			);
			engine.close();
			final Evita reopened = start(configurationOf(paths, null, deferred));
			for (final String catalogName : new String[]{GENERATED_CATALOG, TWIN_CATALOG}) {
				for (final Consumer<EvitaSessionContract> step : HISTORY) {
					reopened.updateCatalog(catalogName, step);
				}
			}
			final TestPaths image = allocatePaths("walTailImage");
			final Map<String, Long> appliedVersions = takeCrashImage(reopened, paths, image);
			reopened.close();

			final EvitaConfiguration imageConfiguration = configurationOf(image, null, deferred);
			final long imageVersion = lastBootstrap(imageConfiguration, GENERATED_CATALOG).catalogVersion();
			assertTrue(
				appliedVersions.get(GENERATED_CATALOG) > imageVersion,
				"the history must be in the write-ahead log only, or the case proves nothing"
			);
			stamp(imageConfiguration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			// the load path migrates the published state - and publishes the version it migrated, not a later one
			withOfflineCatalog(imageConfiguration, GENERATED_CATALOG, service -> Boolean.TRUE);
			final CatalogBootstrap migrated = lastBootstrap(imageConfiguration, GENERATED_CATALOG);
			assertEquals(PersistenceService.STORAGE_PROTOCOL_VERSION, migrated.storageProtocolVersion());
			assertEquals(imageVersion, migrated.catalogVersion());

			final Evita recovered = start(imageConfiguration);
			assertEquals(
				appliedVersions.get(GENERATED_CATALOG), catalogOf(recovered, GENERATED_CATALOG).getVersion(),
				"the load must have replayed the whole tail onto the migrated catalog"
			);
			assertEquals(
				appliedVersions.get(TWIN_CATALOG), catalogOf(recovered, TWIN_CATALOG).getVersion(),
				"the twin must have replayed its whole tail"
			);
			assertMigratedLikeTheTwin("after the replay of the tail", recovered);
		}
	}

	/**
	 * Cases about the flushes of the conversion, driven by direct calls of
	 * {@link VirtualReferencePartitionConversion#convertCollection} with a small flush threshold - the migration
	 * passes the default threshold and nothing else.
	 */
	@Nested
	@DisplayName("The conversion flushes a collection at its threshold")
	class Flushes {
		/**
		 * How many owners reference a target of their own - enough single-owner partitions for the registry to be
		 * stored in several leaf pages and for the conversion to flush many times.
		 */
		private static final int OWNER_COUNT = 150;
		/**
		 * The flush threshold of the case - small enough for the conversion to flush a collection many times.
		 */
		private static final int FLUSH_THRESHOLD = 8;

		/**
		 * The flush count is the one the conversion reports to the operator.
		 */
		@Test
		@DisplayName("a collection flushed during its conversion is published whole by the final flush")
		void shouldPublishACollectionFlushedDuringItsConversionWhole() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("intermediateFlushes"), null, null);
			final Evita engine = start(configuration);
			buildCatalogs(
				engine, CatalogState.ALIVE,
				(session, indexType) -> setUpOwners(session, OWNER_COUNT, 0, false, indexType, false),
				List.of()
			);
			engine.close();

			final CollectionConversion owners = convertOffline(
				configuration, List.of(ENTITY_OWNER), FLUSH_THRESHOLD
			).get(ENTITY_OWNER);
			assertEquals(OWNER_COUNT, owners.convertedPartitions(), owners.toString());
			assertTrue(
				owners.intermediateFlushes() >= 2,
				"the collection must have been flushed during the conversion, or the case proves nothing: " + owners
			);

			final Evita converted = start(configuration);
			assertMigratedLikeTheTwin("after the conversion", converted);
		}
	}

	/**
	 * Defines targets and owners whose reference to them carries a sortable attribute and is indexed in both scopes by
	 * the given index type - optionally reflected on the targets, with everything but the attributes inherited, the
	 * indexing included - and creates the owners, each referencing a target of its own, the first ones the shared
	 * target too. With a reflection, every second target referenced by a single owner is archived, so the reflection
	 * has partitions in both scopes - the stored declaration of an inherited indexing names only the default scope.
	 *
	 * The owners have no prices, no faceted reference and no indexed attribute of their own, so a partition object of
	 * them holds only what one of a reference indexed for filtering holds, whatever the index type.
	 *
	 * @param session           the session to write in
	 * @param ownerCount        how many owners to create
	 * @param sharingOwnerCount how many of them reference the shared target too
	 * @param reflected         TRUE to reflect the reference on the targets
	 * @param indexType         the index type of the reference in both scopes
	 * @param withUrl           TRUE to give every owner a localized URL unique across locales (see {@link #urlOf(int)})
	 */
	private static void setUpOwners(
		@Nonnull EvitaSessionContract session,
		int ownerCount,
		int sharingOwnerCount,
		boolean reflected,
		@Nonnull ReferenceIndexType indexType,
		boolean withUrl
	) {
		setUpOwners(session, ownerCount, sharingOwnerCount, reflected, indexType, withUrl, false);
	}

	/**
	 * The variant of {@link #setUpOwners(EvitaSessionContract, int, int, boolean, ReferenceIndexType, boolean)} that
	 * optionally gives every owner a sort-only moment of its own and every reference a sort-only moment, both the
	 * moment {@link #momentOf(int)} names for the owner. A partition object of a reference indexed for partitioning
	 * then holds an owner sort index of both - with the indexed attributes of the owner the object holds the entity
	 * attribute too.
	 *
	 * @param session           the session to write in
	 * @param ownerCount        how many owners to create
	 * @param sharingOwnerCount how many of them reference the shared target too
	 * @param reflected         TRUE to reflect the reference on the targets
	 * @param indexType         the index type of the reference in both scopes
	 * @param withUrl           TRUE to give every owner a localized URL unique across locales (see {@link #urlOf(int)})
	 * @param withMoments       TRUE to give every owner and every reference a sort-only `OffsetDateTime`
	 */
	private static void setUpOwners(
		@Nonnull EvitaSessionContract session,
		int ownerCount,
		int sharingOwnerCount,
		boolean reflected,
		@Nonnull ReferenceIndexType indexType,
		boolean withUrl,
		boolean withMoments
	) {
		session.defineEntitySchema(ENTITY_TARGET).updateVia(session);
		final EntitySchemaBuilder ownerSchema = session.defineEntitySchema(ENTITY_OWNER)
			.withReferenceToEntity(
				REFERENCE_TARGETS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (indexType == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) {
						whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED);
					} else {
						whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED);
					}
					whichIs.withAttribute(
						ATTRIBUTE_RANK, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					);
					if (withMoments) {
						whichIs.withAttribute(
							ATTRIBUTE_SINCE, OffsetDateTime.class,
							thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
						);
					}
				}
			);
		if (withUrl) {
			ownerSchema
				.withLocale(Locale.ENGLISH)
				.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().unique().nullable());
		}
		if (withMoments) {
			ownerSchema.withAttribute(ATTRIBUTE_MOMENT, OffsetDateTime.class, thatIs -> thatIs.sortable().nullable());
		}
		ownerSchema.updateVia(session);
		if (reflected) {
			// no index type and no components - the reflection inherits them from the reference it reflects
			session.defineEntitySchema(ENTITY_TARGET)
				.withReflectedReferenceToEntity(
					REFERENCE_OWNERS, ENTITY_OWNER, REFERENCE_TARGETS, whichIs -> whichIs.withAttributesInherited()
				)
				.updateVia(session);
		}
		// the targets exist - a reflected reference lives on its target, and a filter by a referenced entity of a
		// managed type the catalog does not hold matches no owner
		for (int target = 1; target <= ownerCount; target++) {
			session.upsertEntity(session.createNewEntity(ENTITY_TARGET, target));
		}
		if (sharingOwnerCount > 0) {
			session.upsertEntity(session.createNewEntity(ENTITY_TARGET, SHARED_TARGET));
		}
		for (int owner = 1; owner <= ownerCount; owner++) {
			final int ownerPrimaryKey = owner;
			final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, owner)
				.setReference(
					REFERENCE_TARGETS, ownerPrimaryKey,
					ref -> {
						ref.setAttribute(ATTRIBUTE_RANK, ownerPrimaryKey);
						if (withMoments) {
							ref.setAttribute(ATTRIBUTE_SINCE, momentOf(ownerPrimaryKey));
						}
					}
				);
			if (owner <= sharingOwnerCount) {
				builder.setReference(
					REFERENCE_TARGETS, SHARED_TARGET,
					ref -> {
						ref.setAttribute(ATTRIBUTE_RANK, -ownerPrimaryKey);
						if (withMoments) {
							ref.setAttribute(ATTRIBUTE_SINCE, momentOf(ownerPrimaryKey));
						}
					}
				);
			}
			if (withUrl) {
				builder.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, urlOf(owner));
			}
			if (withMoments) {
				builder.setAttribute(ATTRIBUTE_MOMENT, momentOf(owner));
			}
			builder.upsertVia(session);
		}
		if (reflected) {
			for (int target = sharingOwnerCount + 2; target <= ownerCount; target += 2) {
				session.archiveEntity(ENTITY_TARGET, target);
			}
		}
	}

	/**
	 * Returns the moment of an owner: owners `2k + 1` and `2k + 2` share one moment, the odd one written at +02:00 and
	 * the even one at +01:00 - so the raw values a release stored order them by their local time, while the normalized
	 * ones tie.
	 *
	 * @param owner the primary key of the owner
	 * @return the moment
	 */
	@Nonnull
	private static OffsetDateTime momentOf(int owner) {
		return NINE_AT_UTC.toInstant()
			.plusSeconds((owner - 1) / 2)
			.atOffset(owner % 2 == 1 ? ZoneOffset.ofHours(2) : ZoneOffset.ofHours(1));
	}

	/**
	 * Returns the URL of an owner - written precomposed, a spelling the unique index of this version stores in
	 * another form, and 2026.2 stored as written.
	 *
	 * @param owner the primary key of the owner
	 * @return the URL
	 */
	@Nonnull
	private static String urlOf(int owner) {
		return Normalizer.normalize("/čaj-" + owner, Normalizer.Form.NFC);
	}

	/**
	 * Writes the image of a catalog of this version writing its single-owner partitions as rows - owners each holding
	 * a partition of their own - and stamps it as a catalog of 2026.2, which no release could have written: its
	 * registry holds rows. The engine that wrote it is closed again.
	 *
	 * @param label names the directories of the image
	 * @return the configuration of an engine over the image
	 */
	@Nonnull
	private EvitaConfiguration writeReleasedImageHoldingARegistryRow(@Nonnull String label) {
		final EvitaConfiguration configuration = configurationOf(allocatePaths(label), null, null);
		final Evita engine = start(configuration);
		buildCatalog(
			engine, GENERATED_CATALOG, ReferenceIndexType.FOR_FILTERING, CatalogState.ALIVE,
			(session, indexType) -> setUpOwners(session, SHARING_OWNER_COUNT, 0, false, indexType, false),
			List.of()
		);
		assertFalse(registeredRowsOf(collectionOf(engine, GENERATED_CATALOG, ENTITY_OWNER)).isEmpty());
		engine.close();
		stamp(configuration, GENERATED_CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
		return configuration;
	}

	/**
	 * Builds both catalogs of a case in one engine: the generated one with the references to convert indexed for
	 * filtering and partitioning, changed to the indexing of the twin once the history ran, and the twin indexed for
	 * filtering from its birth. Asserts the premise of the input: the generated catalog holds no registry row.
	 *
	 * @param engine  the engine
	 * @param state   the state both catalogs end in
	 * @param setUp   the fixture, given the index type of the references to convert
	 * @param history the steps after the fixture
	 */
	private static void buildCatalogs(
		@Nonnull Evita engine,
		@Nonnull CatalogState state,
		@Nonnull BiConsumer<EvitaSessionContract, ReferenceIndexType> setUp,
		@Nonnull List<Consumer<EvitaSessionContract>> history
	) {
		buildCatalog(
			engine, GENERATED_CATALOG, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, state, setUp, history
		);
		buildCatalog(engine, TWIN_CATALOG, ReferenceIndexType.FOR_FILTERING, state, setUp, history);
		changeIndexingToTheTwins(engine);
		for (final String entityType : catalogOf(engine, GENERATED_CATALOG).getEntityTypes()) {
			assertTrue(
				registeredRowsOf(collectionOf(engine, GENERATED_CATALOG, entityType)).isEmpty(),
				"the generated catalog must hold no registry row - no release wrote one"
			);
		}
	}

	/**
	 * Builds one catalog of the engine: defines it, writes the fixture, takes it live when asked to, and runs the
	 * history, every step in a session of its own.
	 *
	 * @param engine      the engine
	 * @param catalogName the catalog
	 * @param indexType   the index type of the references to convert
	 * @param state       the state the catalog ends in
	 * @param setUp       the fixture
	 * @param history     the steps after the fixture
	 */
	private static void buildCatalog(
		@Nonnull Evita engine,
		@Nonnull String catalogName,
		@Nonnull ReferenceIndexType indexType,
		@Nonnull CatalogState state,
		@Nonnull BiConsumer<EvitaSessionContract, ReferenceIndexType> setUp,
		@Nonnull List<Consumer<EvitaSessionContract>> history
	) {
		engine.defineCatalog(catalogName).updateViaNewSession(engine);
		engine.updateCatalog(
			catalogName, (Consumer<EvitaSessionContract>) session -> setUp.accept(session, indexType)
		);
		if (state == CatalogState.ALIVE) {
			engine.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
		}
		for (final Consumer<EvitaSessionContract> step : history) {
			engine.updateCatalog(catalogName, step);
		}
	}

	/**
	 * Changes the indexing of every reference of the generated catalog that differs from its twin's to the twin's -
	 * the index type and the indexed components in every scope, the components stated explicitly, as a change of the
	 * index type alone resets them - first the references declaring their indexing, then the reflected ones still
	 * differing, so a reflection inheriting its indexing keeps inheriting it.
	 *
	 * @param engine the engine
	 */
	private static void changeIndexingToTheTwins(@Nonnull Evita engine) {
		final Map<String, Map<String, List<String>>> twinIndexing = indexingOf(engine, TWIN_CATALOG);
		for (final boolean reflected : new boolean[]{false, true}) {
			final Map<String, Map<String, List<String>>> generatedIndexing = indexingOf(engine, GENERATED_CATALOG);
			final List<ModifyEntitySchemaMutation> changes = new ArrayList<>(4);
			for (final Entry<String, Map<String, List<String>>> entityType : twinIndexing.entrySet()) {
				final EntitySchemaContract generatedSchema = catalogOf(engine, GENERATED_CATALOG)
					.getEntitySchema(entityType.getKey()).orElseThrow();
				final EntitySchemaContract twinSchema = catalogOf(engine, TWIN_CATALOG)
					.getEntitySchema(entityType.getKey()).orElseThrow();
				for (final Entry<String, List<String>> reference : entityType.getValue().entrySet()) {
					final boolean isReflected = generatedSchema.getReference(reference.getKey()).orElseThrow()
						instanceof ReflectedReferenceSchemaContract;
					final List<String> generated = generatedIndexing.get(entityType.getKey()).get(reference.getKey());
					if (isReflected == reflected && !generated.equals(reference.getValue())) {
						final ReferenceSchemaContract twinReference =
							twinSchema.getReference(reference.getKey()).orElseThrow();
						final Scope[] scopes = Scope.values();
						final ScopedReferenceIndexType[] indexTypes = new ScopedReferenceIndexType[scopes.length];
						final ScopedReferenceIndexedComponents[] components =
							new ScopedReferenceIndexedComponents[scopes.length];
						for (int i = 0; i < scopes.length; i++) {
							indexTypes[i] = new ScopedReferenceIndexType(
								scopes[i], twinReference.getReferenceIndexType(scopes[i])
							);
							components[i] = new ScopedReferenceIndexedComponents(
								scopes[i],
								twinReference.getIndexedComponents(scopes[i]).toArray(ReferenceIndexedComponents[]::new)
							);
						}
						changes.add(
							new ModifyEntitySchemaMutation(
								entityType.getKey(),
								new SetReferenceSchemaIndexedMutation(reference.getKey(), indexTypes, components)
							)
						);
					}
				}
			}
			for (final ModifyEntitySchemaMutation change : changes) {
				engine.updateCatalog(
					GENERATED_CATALOG, (Consumer<EvitaSessionContract>) session -> session.updateEntitySchema(change)
				);
			}
		}
		assertEquals(
			twinIndexing, indexingOf(engine, GENERATED_CATALOG),
			"the generated catalog must be indexed as its twin once the indexing changed"
		);
	}

	/**
	 * Describes the indexing of every reference of every collection of a catalog: per scope, in the order of
	 * {@link Scope#values()}, the index type and the indexed components.
	 *
	 * @param engine      the engine
	 * @param catalogName the catalog
	 * @return the indexing by entity type and reference name
	 */
	@Nonnull
	private static Map<String, Map<String, List<String>>> indexingOf(
		@Nonnull Evita engine,
		@Nonnull String catalogName
	) {
		final Map<String, Map<String, List<String>>> indexing = new TreeMap<>();
		final Catalog catalog = catalogOf(engine, catalogName);
		for (final String entityType : catalog.getEntityTypes()) {
			final Map<String, List<String>> references = new TreeMap<>();
			for (final ReferenceSchemaContract reference :
				catalog.getEntitySchema(entityType).orElseThrow().getReferences().values()) {
				final List<String> scopes = new ArrayList<>(Scope.values().length);
				for (final Scope scope : Scope.values()) {
					scopes.add(
						reference.getReferenceIndexType(scope) + " " +
							new TreeSet<>(reference.getIndexedComponents(scope))
					);
				}
				references.put(reference.getName(), scopes);
			}
			indexing.put(entityType, references);
		}
		return indexing;
	}

	/**
	 * Asserts that the migrated catalog represents its single-owner partitions as rows and is indistinguishable from
	 * its twin, keeps the invariants, agrees with its entity bodies and stores what they call for - see the class
	 * documentation.
	 *
	 * @param label  names the moment in failure messages
	 * @param engine the engine hosting both catalogs
	 */
	private static void assertMigratedLikeTheTwin(@Nonnull String label, @Nonnull Evita engine) {
		assertCurrentRepresentation(engine);
		assertUnchanged(
			label + ": the indexes of the migrated catalog against its twin",
			comparableIndexesOf(engine, TWIN_CATALOG), comparableIndexesOf(engine, GENERATED_CATALOG)
		);
		assertUnchanged(
			label + ": the query lines of the migrated catalog against its twin",
			CatalogDescriber.describeQueriesOf(engine, TWIN_CATALOG),
			CatalogDescriber.describeQueriesOf(engine, GENERATED_CATALOG)
		);
		assertInvariants(label, engine, GENERATED_CATALOG);
		engine.queryCatalog(
			GENERATED_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				for (final String entityType : new TreeSet<>(catalogOf(engine, GENERATED_CATALOG).getEntityTypes())) {
					VirtualReferencePartitionRawRowOracle.assertConsistent(
						label, session, collectionOf(engine, GENERATED_CATALOG, entityType)
					);
				}
			}
		);
		assertStoredParts(label, engine, GENERATED_CATALOG);
	}

	/**
	 * Returns the index-level description of a catalog restricted to what the migrated catalog and its twin share:
	 * every line but those describing the attribute indexes and locales of an index object of a partition - the
	 * generated catalog's objects carry the content indexed for partitioning. The key, the primary key and the owners
	 * of every such object stay, as do the registry rows, the advertisements and the membership.
	 *
	 * @param engine      the engine
	 * @param catalogName the catalog
	 * @return the restricted description
	 */
	@Nonnull
	private static SortedMap<String, String> comparableIndexesOf(@Nonnull Evita engine, @Nonnull String catalogName) {
		final SortedMap<String, String> comparable = new TreeMap<>();
		for (final Entry<String, String> line : describeIndexes(engine, catalogName).entrySet()) {
			final String path = line.getKey();
			final int partition = path.indexOf("/partition/");
			if (partition >= 0) {
				final int detail = path.indexOf('/', partition + "/partition/".length());
				if (detail >= 0) {
					final String kind = path.substring(detail + 1);
					if (!kind.equals("key") && !kind.equals("primaryKey") && !kind.equals("members")) {
						continue;
					}
				}
			}
			comparable.put(path, line.getValue());
		}
		return comparable;
	}

	/**
	 * Asserts that both catalogs of the engine carry the current storage protocol - the one representing single-owner
	 * partitions as rows.
	 *
	 * @param engine the engine
	 */
	private static void assertCurrentRepresentation(@Nonnull Evita engine) {
		for (final String catalogName : new String[]{GENERATED_CATALOG, TWIN_CATALOG}) {
			final Catalog catalog = catalogOf(engine, catalogName);
			// through the wildcard: `Catalog` holds the service under the interface's parameterisation
			final CatalogPersistenceService<?, ?, ?> persistenceService = catalog.getPersistenceService();
			final DefaultCatalogPersistenceService defaultPersistenceService =
				(DefaultCatalogPersistenceService) persistenceService;
			assertEquals(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				defaultPersistenceService.getCatalogHeader(defaultPersistenceService.getLastCatalogVersion())
					.storageProtocolVersion(),
				"the storage protocol of `" + catalogName + "`"
			);
		}
	}

	/**
	 * Lists the stored parts of every index object of a partition of a reference whose single-owner partitions are
	 * rows - its manifest, its entity ids and every part its footprint names - with their bytes, split into the objects
	 * of a single owner, which the migration converts, and the others, which it keeps. Read with the engine stopped,
	 * before the catalog is stamped. The footprint is the one the engine reclaims when it drops an index, so the
	 * inventory proves that the published state no longer reaches what the footprint names, not that the footprint
	 * names everything.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @return the inventory
	 */
	@Nonnull
	private static PartitionInventory inventoryPartitions(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName
	) {
		return withOfflineCatalog(
			configuration, catalogName,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final Map<String, EntitySchema> storedSchemas = storedSchemasOf(service);
				final Map<PartitionPart, byte[]> converted = new LinkedHashMap<>(64);
				final Map<PartitionPart, byte[]> kept = new LinkedHashMap<>(64);
				for (final CollectionFileReference reference :
					service.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes()) {
					final String entityType = reference.entityType();
					final EntitySchema schema = EntityCollection.bindReflectedReferences(
						storedSchemas.get(entityType), type -> Optional.ofNullable(storedSchemas.get(type))
					);
					final DefaultEntityCollectionPersistenceService collectionService =
						collectionServiceOf(service, catalogVersion, entityType);
					final OffsetIndexStoragePartPersistenceService storage =
						collectionService.getStoragePartPersistenceService();
					for (final Integer indexPrimaryKey :
						collectionService.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()) {
						final EntityIndexStoragePart manifest = Objects.requireNonNull(
							storage.getStoragePart(catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class)
						);
						if (manifest.getEntityIndexKey().type() != EntityIndexType.REFERENCED_ENTITY) {
							continue;
						}
						final Scope scope = manifest.getEntityIndexKey().scope();
						final String referenceName =
							((RepresentativeReferenceKey) manifest.getEntityIndexKey().discriminator()).referenceName();
						final ReferenceSchemaContract referenceSchema =
							schema.getReference(referenceName).orElseThrow();
						final EntityIndex index = collectionService.readEntityIndex(
							catalogVersion, indexPrimaryKey, schema, false
						);
						final boolean convertible =
							VirtualReferencePartitionWriter.isWrittenVirtually(referenceSchema, scope) &&
								index.getAllPrimaryKeys().size() == 1;
						final Map<PartitionPart, byte[]> target = convertible ? converted : kept;
						final TrappedChanges footprint = new TrappedChanges();
						index.emitFootprintRemovals(footprint);
						final List<PartitionPart> parts = new ArrayList<>(16);
						parts.add(
							new PartitionPart(
								entityType, scope, indexPrimaryKey, indexPrimaryKey, EntityIndexStoragePart.class
							)
						);
						parts.add(
							new PartitionPart(
								entityType, scope, indexPrimaryKey, indexPrimaryKey, EntityIdsStoragePart.class
							)
						);
						final Iterator<StoragePart> it = footprint.getTrappedChangesIterator();
						while (it.hasNext()) {
							if (it.next() instanceof final DeferredRemovalStoragePart removal) {
								parts.add(
									new PartitionPart(
										entityType, scope, indexPrimaryKey,
										removal.computeUniquePartIdAndSet(storage.getReadOnlyKeyCompressor()),
										removal.removedContainerType()
									)
								);
							}
						}
						for (final PartitionPart part : parts) {
							final byte[] bytes = storage.getStoragePartAsBinary(
								catalogVersion, part.partPrimaryKey(), part.type()
							);
							if (bytes != null) {
								target.put(part, bytes);
							}
						}
					}
				}
				return new PartitionInventory(converted, kept);
			}
		);
	}

	/**
	 * Asserts what the published state of the catalog stores of the partitions an inventory listed: no part of an
	 * object the migration converted, every part of an object it kept with the bytes it had.
	 *
	 * @param label         names the moment in failure messages
	 * @param configuration the configuration of the engine
	 * @param inventory     the inventory taken before the migration
	 */
	private static void assertConversionFootprint(
		@Nonnull String label,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull PartitionInventory inventory
	) {
		assertConversionFootprint(label, configuration, inventory, true);
	}

	/**
	 * Asserts what the published state of the catalog stores of the partitions an inventory listed: no part of an
	 * object the migration converted, and - when asked to - every part of an object it kept with the bytes it had.
	 *
	 * @param label         names the moment in failure messages
	 * @param configuration the configuration of the engine
	 * @param inventory     the inventory taken before the migration
	 * @param keptUnchanged TRUE to require the parts of the kept objects byte-identical - FALSE where the re-key of the
	 *                      migration rewrites a part of a kept object on purpose
	 */
	private static void assertConversionFootprint(
		@Nonnull String label,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull PartitionInventory inventory,
		boolean keptUnchanged
	) {
		assertConversionFootprint(label, configuration, inventory, part -> !keptUnchanged);
	}

	/**
	 * Asserts what the published state of the catalog stores of the partitions an inventory listed: no part of an
	 * object the migration converted, and every part of an object it kept with the bytes it had - but the parts another
	 * step of the migration rewrites on purpose, which the caller checks by what they hold.
	 *
	 * @param label              names the moment in failure messages
	 * @param configuration      the configuration of the engine
	 * @param inventory          the inventory taken before the migration
	 * @param rewrittenOnPurpose TRUE for a part of a kept object the migration rewrites on purpose
	 */
	private static void assertConversionFootprint(
		@Nonnull String label,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull PartitionInventory inventory,
		@Nonnull Predicate<PartitionPart> rewrittenOnPurpose
	) {
		withOfflineCatalog(
			configuration, GENERATED_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final List<String> violations = new ArrayList<>(4);
				for (final PartitionPart part : inventory.converted().keySet()) {
					if (
						collectionServiceOf(service, catalogVersion, part.entityType())
							.getStoragePartPersistenceService()
							.containsStoragePart(catalogVersion, part.partPrimaryKey(), part.type())
					) {
						violations.add("still stored: " + part);
					}
				}
				for (final Entry<PartitionPart, byte[]> part : inventory.kept().entrySet()) {
					if (rewrittenOnPurpose.test(part.getKey())) {
						continue;
					}
					final byte[] bytes = collectionServiceOf(service, catalogVersion, part.getKey().entityType())
						.getStoragePartPersistenceService()
						.getStoragePartAsBinary(
							catalogVersion, part.getKey().partPrimaryKey(), part.getKey().type()
						);
					if (!Arrays.equals(part.getValue(), bytes)) {
						violations.add("changed or gone: " + part.getKey());
					}
				}
				if (!violations.isEmpty()) {
					fail(
						label + ": " + violations.size() + " part(s) of partition objects:\n" +
							String.join("\n", violations.subList(0, Math.min(violations.size(), 40)))
					);
				}
				return Boolean.TRUE;
			}
		);
	}

	/**
	 * Converts the single-owner partitions of the given collections of the generated catalog with the engine stopped,
	 * by direct calls of the conversion, and publishes them through the same writes the migration publishes with: the
	 * new collection headers put into the catalog data store, the catalog header written once more and one bootstrap
	 * record.
	 *
	 * @param configuration  the configuration of the engine
	 * @param entityTypes    the collections to convert, in this order
	 * @param flushThreshold the flush threshold of the conversion
	 * @return what the conversion did with each collection
	 */
	@Nonnull
	private static Map<String, CollectionConversion> convertOffline(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull List<String> entityTypes,
		int flushThreshold
	) {
		final int catalogFileIndex = lastBootstrap(configuration, GENERATED_CATALOG).catalogFileIndex();
		return withOfflineCatalog(
			configuration, GENERATED_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				final Map<String, EntitySchema> storedSchemas = storedSchemasOf(service);
				final Map<String, CollectionFileReference> collectionFileIndex =
					new HashMap<>(header.collectionFileIndex());
				final Map<String, CollectionConversion> conversions = new LinkedHashMap<>(entityTypes.size());
				for (final String entityType : entityTypes) {
					final CollectionFileReference reference = collectionFileIndex.get(entityType);
					final EntityCollectionFileHeader collectionHeader = EntityCollectionHeaderReconciler.reconcile(
						GENERATED_CATALOG, reference,
						Objects.requireNonNull(
							catalogParts.getStoragePart(
								catalogVersion, reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class
							)
						)
					);
					try (
						DefaultEntityCollectionPersistenceService collectionService =
							service.createEntityCollectionPersistenceService(collectionHeader)
					) {
						final CollectionConversion conversion = VirtualReferencePartitionConversion.convertCollection(
							catalogVersion, collectionHeader, collectionService,
							EntityCollection.bindReflectedReferences(
								storedSchemas.get(entityType), type -> Optional.ofNullable(storedSchemas.get(type))
							),
							flushThreshold
						);
						conversions.put(entityType, conversion);
						if (conversion.changed()) {
							final EntityCollectionFileHeader convertedHeader =
								collectionService.getEntityCollectionHeader();
							catalogParts.putStoragePart(catalogVersion, convertedHeader);
							collectionFileIndex.put(
								entityType,
								new CollectionFileReference(
									entityType, reference.entityTypePrimaryKey(), reference.fileIndex(),
									convertedHeader.fileLocation()
								)
							);
						}
					}
				}
				catalogParts.writeCatalogHeader(
					PersistenceService.STORAGE_PROTOCOL_VERSION, catalogVersion, service.getCatalogStoragePath(),
					header.walFileReference(), collectionFileIndex, header.catalogId(), header.catalogName(),
					header.catalogState(), header.lastEntityCollectionPrimaryKey()
				);
				service.recordBootstrap(catalogVersion, GENERATED_CATALOG, catalogFileIndex, null);
				return conversions;
			}
		);
	}

	/**
	 * Turns the owner unique index of the URLs of the generated catalog into the state 2026.2 would have left on disk -
	 * every value in the spelling 2026.2 stored, in 2026.2's order - and publishes it under the current storage
	 * protocol, with the same flush-and-re-address sequence the migration performs on a rewritten collection.
	 *
	 * @param configuration the configuration of the engine
	 * @param spellings     the spellings 2026.2 stored
	 */
	private static void respellOwnerUniqueIndex(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull Spellings spellings
	) {
		final int catalogFileIndex = lastBootstrap(configuration, GENERATED_CATALOG).catalogFileIndex();
		withOfflineCatalog(
			configuration, GENERATED_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				final DefaultEntityCollectionPersistenceService collectionService =
					collectionServiceOf(service, catalogVersion, ENTITY_OWNER);
				final Set<String> respelled = new HashSet<>(2);
				final List<StoredUniqueIndex> uniqueIndexes = Migration_2026_3_UniqueUpgradeRoundTripTest
					.readOwnerUniqueIndexes(catalogVersion, collectionService);
				for (final StoredUniqueIndex index : uniqueIndexes) {
					Migration_2026_3_UniqueUpgradeRoundTripTest.respellIntoLegacyOrder(
						index, spellings, Shape.INLINE, respelled
					);
				}
				assertEquals(
					Set.of(ENTITY_OWNER + " " + ATTRIBUTE_URL), respelled,
					"the owner unique index must hold raw spellings, or the re-key has nothing to do"
				);
				final OffsetIndexDescriptor descriptor = collectionService.flush(
					catalogVersion,
					new NoChangeHeaderInfoSupplier(collectionService.getEntityCollectionHeader())
				);
				catalogParts.putStoragePart(catalogVersion, collectionService.getEntityCollectionHeader());
				final Map<String, CollectionFileReference> collectionFileIndex =
					new HashMap<>(header.collectionFileIndex());
				final CollectionFileReference reference = collectionFileIndex.get(ENTITY_OWNER);
				collectionFileIndex.put(
					ENTITY_OWNER,
					new CollectionFileReference(
						ENTITY_OWNER, reference.entityTypePrimaryKey(), reference.fileIndex(), descriptor.fileLocation()
					)
				);
				catalogParts.writeCatalogHeader(
					PersistenceService.STORAGE_PROTOCOL_VERSION, catalogVersion, service.getCatalogStoragePath(),
					header.walFileReference(), collectionFileIndex, header.catalogId(), header.catalogName(),
					header.catalogState(), header.lastEntityCollectionPrimaryKey()
				);
				service.recordBootstrap(catalogVersion, GENERATED_CATALOG, catalogFileIndex, null);
				return Boolean.TRUE;
			}
		);
	}

	/**
	 * Asserts whether the owner unique indexes of the URLs of the generated catalog store every value in the canonical
	 * form of this version, or every value in a spelling 2026.2 stored. The URL is an attribute of the owner indexed
	 * for partitioning, so besides the global index every index object of a partition of the owners holds its own
	 * owner unique index of it - each of them is respelled before the migration and re-keyed by it.
	 *
	 * @param label         names the moment in failure messages
	 * @param configuration the configuration of the engine
	 * @param canonical     TRUE when every value must be canonical, FALSE when every one must be raw
	 */
	private static void assertOwnerUrlsStored(
		@Nonnull String label,
		@Nonnull EvitaConfiguration configuration,
		boolean canonical
	) {
		withOfflineCatalog(
			configuration, GENERATED_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final List<StoredUniqueIndex> indexes = Migration_2026_3_UniqueUpgradeRoundTripTest
					.readOwnerUniqueIndexes(catalogVersion, collectionServiceOf(service, catalogVersion, ENTITY_OWNER));
				int nonCanonical = 0;
				int values = 0;
				for (final StoredUniqueIndex index : indexes) {
					assertEquals(ENTITY_OWNER + " " + ATTRIBUTE_URL, index.label(), label);
					for (final Serializable[] page : index.pageValues()) {
						for (final Serializable value : page) {
							values++;
							if (!UniqueIndex.toPersistedValue(String.class, 0, value).equals(value)) {
								nonCanonical++;
							}
						}
					}
				}
				assertTrue(
					values >= REFLECTED_OWNER_COUNT, label + ": the global index alone holds the URL of every owner"
				);
				if (canonical) {
					assertEquals(0, nonCanonical, label + ": every URL must be stored canonically");
				} else {
					assertEquals(values, nonCanonical, label + ": every URL must still be stored raw");
				}
				return Boolean.TRUE;
			}
		);
	}

	/**
	 * Asserts that a compacting flush would have compacted at least one data store the migration wrote - so the
	 * thresholds of the case are low enough to show a compaction had the migration run one.
	 *
	 * @param configuration the configuration of the engine
	 * @param storage       the storage options of the case
	 * @param entityTypes   the collections the migration converted
	 */
	private static void assertCompactionWouldHaveRun(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull StorageOptions storage,
		@Nonnull Set<String> entityTypes
	) {
		final List<String> eligible = withOfflineCatalog(
			configuration, GENERATED_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final List<String> result = new ArrayList<>(2);
				for (final CollectionFileReference reference :
					service.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes()) {
					if (entityTypes.contains(reference.entityType())) {
						final EntityCollectionFileHeader header = Objects.requireNonNull(
							service.getStoragePartPersistenceService(catalogVersion).getStoragePart(
								catalogVersion, reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class
							)
						);
						final long fileSize = reference.toFilePath(catalogFolder(configuration, GENERATED_CATALOG))
							.toFile().length();
						if (
							DefaultCatalogPersistenceService.shouldCompact(
								fileSize > storage.fileSizeCompactionThresholdBytes(), header.activeRecordShare(),
								storage.minimalActiveRecordShare(), storage.maxWasteActiveShare(), true
							)
						) {
							result.add(reference.entityType());
						}
					}
				}
				return result;
			}
		);
		assertFalse(
			eligible.isEmpty(),
			"a data store the migration wrote must be one a compacting flush would compact, or the case cannot show " +
				"that the migration compacts none"
		);
	}

	/**
	 * Returns the stored schema of every collection of a catalog opened with the engine stopped, before the reflected
	 * references are bound. Every collection is read through a data store opened for the read alone and closed again,
	 * so the catalog keeps no data store open a later write could compete with.
	 *
	 * @param service the persistence service of the catalog
	 * @return the schemas by entity type
	 */
	@Nonnull
	private static Map<String, EntitySchema> storedSchemasOf(@Nonnull DefaultCatalogPersistenceService service) {
		final long catalogVersion = service.getLastCatalogVersion();
		final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
			service.getStoragePartPersistenceService(catalogVersion);
		final Map<String, EntitySchema> schemas = new HashMap<>(8);
		for (final CollectionFileReference reference :
			service.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes()) {
			final EntityCollectionFileHeader collectionHeader = EntityCollectionHeaderReconciler.reconcile(
				GENERATED_CATALOG, reference,
				Objects.requireNonNull(
					catalogParts.getStoragePart(
						catalogVersion, reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class
					)
				)
			);
			try (
				DefaultEntityCollectionPersistenceService collectionService =
					service.createEntityCollectionPersistenceService(collectionHeader)
			) {
				schemas.put(
					reference.entityType(),
					Objects.requireNonNull(
						collectionService.getStoragePartPersistenceService()
							.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class)
					).entitySchema()
				);
			}
		}
		return schemas;
	}

	/**
	 * Returns the persistence service of a collection of a catalog opened with the engine stopped.
	 *
	 * @param service        the persistence service of the catalog
	 * @param catalogVersion the catalog version
	 * @param entityType     the entity type
	 * @return the persistence service of the collection
	 */
	@Nonnull
	private static DefaultEntityCollectionPersistenceService collectionServiceOf(
		@Nonnull DefaultCatalogPersistenceService service,
		long catalogVersion,
		@Nonnull String entityType
	) {
		final CollectionFileReference reference = Objects.requireNonNull(
			service.getCatalogHeader(catalogVersion).collectionFileIndex().get(entityType),
			"the catalog has no collection `" + entityType + "`"
		);
		return service.getOrCreateEntityCollectionPersistenceService(
			catalogVersion, entityType, reference.entityTypePrimaryKey()
		);
	}

	/**
	 * Takes the image a crash would leave of the running engine - a copy of its storage directory taken while it is
	 * quiescent - once every catalog has incorporated everything its write-ahead log holds, and accepts it only when no
	 * file changed while it was being copied.
	 *
	 * @param engine the running engine
	 * @param paths  the directories of the engine
	 * @param image  the directories receiving the copy
	 * @return the catalog version each catalog had applied when the copy was taken
	 */
	@Nonnull
	private static Map<String, Long> takeCrashImage(
		@Nonnull Evita engine,
		@Nonnull TestPaths paths,
		@Nonnull TestPaths image
	) {
		final Map<String, Long> appliedVersions = new TreeMap<>();
		final Map<String, Catalog> catalogs = new TreeMap<>();
		for (final String catalogName : new String[]{GENERATED_CATALOG, TWIN_CATALOG}) {
			await()
				.atMost(30, TimeUnit.SECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(
					() -> catalogOf(engine, catalogName).getVersion() ==
						catalogOf(engine, catalogName).getLastCatalogVersionInMutationStream()
				);
			final Catalog catalog = catalogOf(engine, catalogName);
			appliedVersions.put(catalogName, catalog.getVersion());
			catalogs.put(catalogName, catalog);
		}
		final SortedMap<String, String> beforeCopy = listFiles(paths.storage());
		CatalogReloadTestSupport.copyRecursively(paths.storage(), image.storage());
		assertEquals(
			beforeCopy, listFiles(paths.storage()),
			"the storage changed while it was being copied - the image is not the state of one moment"
		);
		assertEquals(beforeCopy, listFiles(image.storage()), "the image must hold every file of the storage, whole");
		for (final Entry<String, Catalog> catalog : catalogs.entrySet()) {
			assertSame(
				catalog.getValue(), catalogOf(engine, catalog.getKey()),
				"the catalog published a new version while its storage was being copied"
			);
		}
		return appliedVersions;
	}

	/**
	 * Lists every file of a directory tree with its length and modification time.
	 *
	 * @param root the directory
	 * @return the description of every file, by its path relative to the root
	 */
	@Nonnull
	private static SortedMap<String, String> listFiles(@Nonnull Path root) {
		final SortedMap<String, String> files = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(root)) {
			for (final Path file : walk.filter(Files::isRegularFile).toList()) {
				files.put(
					root.relativize(file).toString(),
					Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis()
				);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return files;
	}

	/**
	 * Returns the first throwable of type `type` in the cause chain of `throwable`, failing the test when there is
	 * none.
	 *
	 * @param throwable the throwable
	 * @param type      the type to find
	 * @param <T>       the type to find
	 * @return the throwable found
	 */
	@Nonnull
	private static <T extends Throwable> T findCause(@Nonnull Throwable throwable, @Nonnull Class<T> type) {
		Throwable current = throwable;
		for (int depth = 0; current != null && depth < 16; depth++) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return fail("No " + type.getSimpleName() + " in the cause chain of " + throwable, throwable);
	}

	/**
	 * Builds the configuration of an engine over the directories.
	 *
	 * @param paths       the directories
	 * @param storage     storage options replacing the default ones, `null` for the defaults
	 * @param transaction transaction options replacing the default ones, `null` for the defaults
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(
		@Nonnull TestPaths paths,
		@Nullable StorageOptions storage,
		@Nullable TransactionOptions transaction
	) {
		final EvitaConfiguration.Builder builder = newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build());
		if (storage != null) {
			builder.storage(storage);
		}
		if (transaction != null) {
			builder.transaction(transaction);
		}
		return builder.build();
	}

	/**
	 * Starts an engine and waits until it has loaded every catalog. The engine is closed after the test.
	 *
	 * @param configuration the configuration of the engine
	 * @return the engine
	 */
	@Nonnull
	private Evita start(@Nonnull EvitaConfiguration configuration) {
		if (this.evita != null && this.evita.isActive()) {
			fail("The engine of the test must be closed before another one is started.");
		}
		final Evita started = new Evita(configuration);
		this.evita = started;
		started.waitUntilFullyInitialized();
		for (final String catalogName : started.getCatalogNames()) {
			await()
				.atMost(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.pollInterval(50, TimeUnit.MILLISECONDS)
				.until(() -> started.getCatalogInstance(catalogName).orElse(null) instanceof Catalog);
		}
		return started;
	}

	/**
	 * Allocates fresh test paths, removed after the test.
	 *
	 * @param label names the directories
	 * @return the paths
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths("Migration_2026_3_VirtualPartitions_" + label);
		this.allocatedPaths.add(paths);
		return paths;
	}

	/**
	 * One stored part of an index object of a partition.
	 *
	 * @param entityType          the collection
	 * @param scope               the scope of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param partPrimaryKey      the primary key of the part
	 * @param type                the type of the part
	 */
	private record PartitionPart(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		int partitionPrimaryKey,
		long partPrimaryKey,
		@Nonnull Class<? extends StoragePart> type
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.entityType + "/" + this.scope + "/partition " + this.partitionPrimaryKey + ": " +
				this.type.getSimpleName() + " " + this.partPrimaryKey;
		}
	}

	/**
	 * The stored parts of the partition objects of a catalog, with their bytes - see
	 * {@link #inventoryPartitions(EvitaConfiguration, String)}.
	 *
	 * @param converted the parts of the objects the migration converts
	 * @param kept      the parts of the objects it keeps
	 */
	private record PartitionInventory(
		@Nonnull Map<PartitionPart, byte[]> converted,
		@Nonnull Map<PartitionPart, byte[]> kept
	) {

		/**
		 * Returns how many partition objects of the collection the migration converts.
		 *
		 * @param entityType the collection
		 * @return the count
		 */
		int convertedCount(@Nonnull String entityType) {
			return (int) manifestsOfConverted().stream().filter(it -> it.entityType().equals(entityType)).count();
		}

		/**
		 * Returns the manifests of the partition objects the migration converts.
		 *
		 * @return the manifests
		 */
		@Nonnull
		List<PartitionPart> manifestsOfConverted() {
			return this.converted.keySet().stream().filter(it -> it.type() == EntityIndexStoragePart.class).toList();
		}

		/**
		 * Returns the collections holding a partition object the migration converts.
		 *
		 * @return the entity types
		 */
		@Nonnull
		Set<String> collectionsWithConverted() {
			final Set<String> entityTypes = new TreeSet<>();
			for (final PartitionPart part : manifestsOfConverted()) {
				entityTypes.add(part.entityType());
			}
			return entityTypes;
		}

		/**
		 * Returns TRUE when a converted object stores a part other than its manifest and its entity ids - one only its
		 * footprint names.
		 *
		 * @return TRUE when such a part is stored
		 */
		boolean hasSubsidiaryParts() {
			return this.converted.keySet().stream().anyMatch(
				it -> it.type() != EntityIndexStoragePart.class && it.type() != EntityIdsStoragePart.class
			);
		}

		@Nonnull
		@Override
		public String toString() {
			return manifestsOfConverted().size() + " object(s) to convert in " + collectionsWithConverted() + ", " +
				this.converted.size() + " part(s) of them, " + this.kept.size() + " part(s) of objects kept";
		}
	}

	/**
	 * The failure the publication cases inject.
	 */
	private static final class InjectedFailure extends RuntimeException {
		@Serial private static final long serialVersionUID = -2213358791874539637L;

		InjectedFailure() {
			super("Injected failure of the publication.");
		}
	}

}
