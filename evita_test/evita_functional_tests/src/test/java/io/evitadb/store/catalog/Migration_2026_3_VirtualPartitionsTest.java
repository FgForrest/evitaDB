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
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.index.mutation.local.VirtualReferencePartitionWriter;
import io.evitadb.index.virtual.DifferentialEngines;
import io.evitadb.index.virtual.SingleOwnerPartitionFixture;
import io.evitadb.index.virtual.VirtualReferencePartitionMirrorTest;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.VirtualReferencePartitionConversion.Checkpoint;
import io.evitadb.store.catalog.VirtualReferencePartitionConversion.CollectionConversion;
import io.evitadb.store.catalog.VirtualReferencePartitionConversion.Observer;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.settings.StorageSettings;
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
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ATTRIBUTE_ORDER;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_CATEGORY;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.ENTITY_PRODUCT;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.REFERENCE_CATEGORIES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.newProduct;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.updateProduct;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertInvariants;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertUnchanged;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.catalogOf;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.collectionOf;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.describeIndexes;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.registeredRowsOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the conversion of a catalog representing its single-owner reference partitions by index objects (storage
 * protocol 7) into one representing them as registry rows (storage protocol 8) -
 * {@link VirtualReferencePartitionConversion} through
 * {@link DefaultCatalogPersistenceService#convertToVirtualReferencePartitions()} - over catalogs built through the
 * engine, with no binary fixture.
 *
 * Every case builds two catalogs by the same history in one engine: one representing its single-owner partitions by
 * index objects - the format this release writes a catalog in by default, so a genuine source of the conversion - and
 * its virtual twin. The engine is closed, the catalog of index objects is converted offline, and the engine is started
 * again. The converted catalog must then be indistinguishable from its twin: the same registry rows under the same
 * primary keys, with the same owners, representative attribute values, sort values and chain predecessors, the same
 * index objects of the partitions with several owners, the same membership, the same answers to every query line of
 * {@link DifferentialEngines}, and the invariants every virtual catalog keeps
 * ({@link io.evitadb.index.virtual.VirtualPartitionInvariants}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — single-owner partitions converted from index objects into registry rows (v7→v8)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(REFERENCE)
@SuppressWarnings("removal") // the conversion is @Deprecated(forRemoval) with the migrations; testing it is the point
class Migration_2026_3_VirtualPartitionsTest implements EvitaTestSupport {
	/**
	 * The catalog representing its single-owner partitions by index objects - the one converted.
	 */
	private static final String OBJECTS_CATALOG = "objectsCatalog";
	/**
	 * The catalog representing its single-owner partitions virtually from its birth - the twin.
	 */
	private static final String VIRTUAL_CATALOG = "virtualCatalog";
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
	 * The reference of the owners, indexed for filtering in both scopes.
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
		EVERY_SORT_TYPE(VirtualReferencePartitionMirrorTest::setUpTypes),
		/**
		 * Both chain types on a reference reflected with its attributes inherited.
		 */
		CHAINS(VirtualReferencePartitionMirrorTest::setUpChains);

		/**
		 * Defines the schema and writes the data.
		 */
		private final Consumer<EvitaSessionContract> setUp;

		ValueFixture(@Nonnull Consumer<EvitaSessionContract> setUp) {
			this.setUp = setUp;
		}
	}

	/**
	 * Cases comparing the converted catalog with its virtual twin.
	 */
	@Nested
	@DisplayName("The converted catalog equals its virtual twin")
	class ConvertedEqualsTwin {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("every single-owner partition becomes the row the twin wrote, the reflected ones included")
		void shouldConvertEverySingleOwnerPartitionIntoTheRowTheTwinWrote(CatalogState state) {
			final TestPaths paths = allocatePaths("twin_" + state.name());
			final EvitaConfiguration configuration = configurationOf(paths, null, null);
			final Evita engine = start(configuration);
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				buildCatalog(
					engine, catalogName, VIRTUAL_CATALOG.equals(catalogName), state,
					SingleOwnerPartitionFixture::setUpWithEveryReadRoute, HISTORY
				);
			}
			assertTwinsAnswerAlike(engine, "before the conversion");
			engine.close();

			final RecordingObserver observer = new RecordingObserver(false);
			assertTrue(convertOffline(configuration, OBJECTS_CATALOG, observer));

			final CollectionConversion products = observer.conversionOf(ENTITY_PRODUCT);
			final CollectionConversion categories = observer.conversionOf(ENTITY_CATEGORY);
			assertTrue(products.convertedPartitions() > 0, products.toString());
			assertTrue(
				products.sharedPartitions() > 0, "partitions with several owners must stay objects: " + products
			);
			assertTrue(products.partitionedPartitions() > 0, "the brand partitions must stay objects: " + products);
			assertTrue(categories.convertedPartitions() > 0, "the reflected partitions must convert: " + categories);
			assertNoLeftovers(observer);

			final Evita restarted = start(configuration);
			assertConvertedLikeTheTwin("after the conversion", restarted);
		}

		@Test
		@DisplayName("a reflected reference is classified by the indexing it inherits, not by what it declares")
		void shouldClassifyAReflectedReferenceByTheIndexingItInherits() {
			final TestPaths paths = allocatePaths("inheritedIndexing");
			final EvitaConfiguration configuration = configurationOf(paths, null, null);
			final Evita engine = start(configuration);
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				buildCatalog(
					engine, catalogName, VIRTUAL_CATALOG.equals(catalogName), CatalogState.ALIVE,
					session -> setUpOwners(session, REFLECTED_OWNER_COUNT, SHARING_OWNER_COUNT, true), List.of()
				);
			}
			assertTwinsAnswerAlike(engine, "before the conversion");
			// the reflected reference inherits what makes its partitions virtual, so its stored declaration alone does
			// not say so - the conversion must bind it to the reference it reflects before it classifies anything
			final ReferenceSchemaContract boundOwners = catalogOf(engine, OBJECTS_CATALOG)
				.getEntitySchema(ENTITY_TARGET).orElseThrow()
				.getReference(REFERENCE_OWNERS).orElseThrow();
			assertTrue(VirtualReferencePartitionWriter.isShadowed(boundOwners, Scope.ARCHIVED));
			engine.close();
			final ReferenceSchemaContract storedOwners = withOfflineCatalog(
				configuration, OBJECTS_CATALOG,
				service -> readStoredSchema(service, ENTITY_TARGET).getReference(REFERENCE_OWNERS).orElseThrow()
			);
			assertFalse(
				VirtualReferencePartitionWriter.isShadowed(storedOwners, Scope.ARCHIVED),
				"the stored declaration of the reflected reference must not classify its partitions by itself, or " +
					"the case does not prove that the conversion binds the schema"
			);

			final RecordingObserver observer = new RecordingObserver(false);
			assertTrue(
				convertOffline(configuration, OBJECTS_CATALOG, observer)
			);
			final CollectionConversion targets = observer.conversionOf(ENTITY_TARGET);
			assertEquals(
				REFLECTED_OWNER_COUNT - SHARING_OWNER_COUNT, targets.convertedPartitions(),
				"every reflected partition with a single owner must convert: " + targets
			);
			assertEquals(SHARING_OWNER_COUNT, targets.sharedPartitions(), targets.toString());
			assertTrue(
				observer.registries.contains(ENTITY_TARGET + "/" + REFERENCE_OWNERS + "/" + Scope.ARCHIVED),
				"the archived targets must hold reflected partitions - the scope the stored declaration misses"
			);
			assertNoLeftovers(observer);

			final Evita restarted = start(configuration);
			assertConvertedLikeTheTwin("after the conversion", restarted);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(ValueFixture.class)
		@DisplayName("every sort value, compound value and chain predecessor of a row is the one the twin wrote")
		void shouldConvertEveryKindOfRowValue(ValueFixture fixture) {
			final TestPaths paths = allocatePaths("values_" + fixture.name());
			final EvitaConfiguration configuration = configurationOf(paths, null, null);
			final Evita engine = start(configuration);
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				buildCatalog(
					engine, catalogName, VIRTUAL_CATALOG.equals(catalogName), CatalogState.ALIVE, fixture.setUp,
					List.of()
				);
			}
			assertTwinsAnswerAlike(engine, "before the conversion");
			engine.close();

			final RecordingObserver observer = new RecordingObserver(false);
			assertTrue(convertOffline(configuration, OBJECTS_CATALOG, observer));
			assertTrue(observer.conversionOf(ENTITY_PRODUCT).convertedPartitions() > 0);
			assertNoLeftovers(observer);

			final Evita restarted = start(configuration);
			assertConvertedLikeTheTwin("after the conversion", restarted);
		}

		@Test
		@DisplayName("registry rows a catalog of index objects kept stale are discarded and rebuilt from the objects")
		void shouldRebuildStaleRowsOfACatalogOfIndexObjects() {
			final TestPaths paths = allocatePaths("staleRows");
			final EvitaConfiguration configuration = configurationOf(paths, null, null);
			final Evita engine = start(configuration);
			// the catalog of index objects keeps its registry next to its objects while the fixture is written, and
			// stops keeping it for the history - its rows are stale from then on
			engine.createCatalogWithVirtualReferencePartitions(OBJECTS_CATALOG, false);
			engine.defineCatalog(OBJECTS_CATALOG).updateViaNewSession(engine);
			for (final String entityType : SingleOwnerPartitionFixture.ENTITY_TYPES) {
				catalogOf(engine, OBJECTS_CATALOG).getVirtualReferencePartitionMaintenance()
					.setMaintained(entityType, true);
			}
			engine.updateCatalog(OBJECTS_CATALOG, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
			for (final String entityType : SingleOwnerPartitionFixture.ENTITY_TYPES) {
				catalogOf(engine, OBJECTS_CATALOG).getVirtualReferencePartitionMaintenance()
					.setMaintained(entityType, false);
			}
			for (final Consumer<EvitaSessionContract> step : HISTORY) {
				engine.updateCatalog(OBJECTS_CATALOG, step);
			}
			buildCatalog(
				engine, VIRTUAL_CATALOG, true, CatalogState.WARMING_UP,
				SingleOwnerPartitionFixture::setUpWithReflectedProducts, HISTORY
			);
			assertTwinsAnswerAlike(engine, "before the conversion");
			final SortedMap<Integer, ?> staleRows =
				registeredRowsOf(collectionOf(engine, OBJECTS_CATALOG, ENTITY_PRODUCT));
			assertFalse(staleRows.isEmpty(), "the catalog of index objects must hold registry rows");
			assertNotEquals(
				registeredRowsOf(collectionOf(engine, VIRTUAL_CATALOG, ENTITY_PRODUCT)), staleRows,
				"the rows of the catalog of index objects must be stale, or the case proves nothing"
			);
			engine.close();

			final RecordingObserver observer = new RecordingObserver(false);
			assertTrue(convertOffline(configuration, OBJECTS_CATALOG, observer));
			assertTrue(observer.conversionOf(ENTITY_PRODUCT).discardedRows() > 0);

			final Evita restarted = start(configuration);
			assertConvertedLikeTheTwin("after the conversion", restarted);
		}
	}

	/**
	 * Cases about what the conversion publishes, and when.
	 */
	@Nested
	@DisplayName("The conversion publishes once, and only a catalog of index objects")
	class Publication {

		@Test
		@DisplayName("a virtual catalog is left untouched: nothing is read, every file keeps its bytes")
		void shouldLeaveAVirtualCatalogUntouched() {
			final TestPaths paths = allocatePaths("untouched");
			final EvitaConfiguration configuration = configurationOf(paths, null, null);
			final Evita engine = start(configuration);
			buildCatalog(
				engine, VIRTUAL_CATALOG, true, CatalogState.ALIVE,
				SingleOwnerPartitionFixture::setUpWithReflectedProducts, HISTORY
			);
			final SortedMap<String, String> indexes = describeIndexes(engine, VIRTUAL_CATALOG);
			engine.close();
			final Map<String, StoredFile> before = storedFiles(paths, VIRTUAL_CATALOG);

			final RecordingObserver observer = new RecordingObserver(false);
			assertFalse(convertOffline(configuration, VIRTUAL_CATALOG, observer));

			assertTrue(observer.isEmpty(), "nothing of a virtual catalog may be read: " + observer);
			assertEquals(before, storedFiles(paths, VIRTUAL_CATALOG), "every file must keep its bytes");
			final Evita restarted = start(configuration);
			assertRepresentation(restarted, VIRTUAL_CATALOG, true);
			assertUnchanged("after the untouched conversion", indexes, describeIndexes(restarted, VIRTUAL_CATALOG));
			assertInvariants("after the untouched conversion", restarted, VIRTUAL_CATALOG);
		}

		@Test
		@DisplayName("a failure between two collections leaves the published catalog intact, and a rerun converts it")
		void shouldLeaveThePublishedCatalogIntactWhenTheConversionFailsBetweenTwoCollections() {
			final TestPaths paths = allocatePaths("crash");
			// thresholds a compacting flush would act on: were the conversion to compact a data store or retire a
			// file on its way, the files of the failed run would show a new generation or a missing file
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
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				buildCatalog(
					engine, catalogName, VIRTUAL_CATALOG.equals(catalogName), CatalogState.WARMING_UP,
					SingleOwnerPartitionFixture::setUpWithReflectedProducts, HISTORY
				);
			}
			final SortedMap<String, String> indexes = describeIndexes(engine, OBJECTS_CATALOG);
			final SortedMap<String, String> queries = DifferentialEngines.describeQueriesOf(engine, OBJECTS_CATALOG);
			engine.close();
			// opening the catalog lets the store settle what it owes on its own - with time travel off it trims the
			// bootstrap file to the published record - so the files are taken after one plain open, and everything
			// that changes after it is the conversion's doing
			withOfflineCatalog(configuration, OBJECTS_CATALOG, service -> Boolean.TRUE);
			final Map<String, StoredFile> before = storedFiles(paths, OBJECTS_CATALOG);
			final CatalogBootstrap publishedBefore = readLastBootstrap(configuration, OBJECTS_CATALOG);

			// the failure strikes once the first collection with partitions to convert is converted and closed, while
			// the other one is still waiting
			final RecordingObserver failing = new RecordingObserver(true);
			assertThrows(
				InjectedFailure.class,
				() -> convertOffline(configuration, OBJECTS_CATALOG, failing)
			);
			assertEquals(1, failing.conversions.size(), "the failure must strike after the first converted collection");
			final String convertedType = failing.conversions.getFirst().entityType();
			final String pendingType = ENTITY_PRODUCT.equals(convertedType) ? ENTITY_CATEGORY : ENTITY_PRODUCT;
			assertTrue(failing.conversions.getFirst().convertedPartitions() > 0);
			assertTrue(
				failing.partitionReads.stream().noneMatch(read -> read.startsWith(pendingType + "/")),
				"the collection `" + pendingType + "` must still have been waiting for its conversion"
			);
			assertOnlyAppended(before, storedFiles(paths, OBJECTS_CATALOG), true);
			assertEquals(
				publishedBefore, readLastBootstrap(configuration, OBJECTS_CATALOG),
				"nothing may be published by a conversion that failed"
			);

			final Evita afterFailure = start(configuration);
			assertRepresentation(afterFailure, OBJECTS_CATALOG, false);
			assertUnchanged("after the failed conversion", indexes, describeIndexes(afterFailure, OBJECTS_CATALOG));
			assertUnchanged(
				"after the failed conversion: the query lines", queries,
				DifferentialEngines.describeQueriesOf(afterFailure, OBJECTS_CATALOG)
			);
			afterFailure.close();

			final Map<String, StoredFile> beforeRerun = storedFiles(paths, OBJECTS_CATALOG);
			final RecordingObserver rerun = new RecordingObserver(false);
			assertTrue(convertOffline(configuration, OBJECTS_CATALOG, rerun));
			assertOnlyAppended(beforeRerun, storedFiles(paths, OBJECTS_CATALOG), false);
			assertCompactionWouldHaveRun(configuration, compacting, rerun);

			final Evita restarted = start(configuration);
			assertConvertedLikeTheTwin("after the rerun", restarted);
		}

		@Test
		@Tag(WAL)
		@DisplayName("the write-ahead log tail of a converted catalog replays onto it as the twin replays its own")
		void shouldReplayTheWriteAheadLogTailOntoTheConvertedCatalog() {
			final TestPaths paths = allocatePaths("walTail");
			final TransactionOptions deferred = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			final Evita engine = start(configurationOf(paths, null, deferred));
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				buildCatalog(
					engine, catalogName, VIRTUAL_CATALOG.equals(catalogName), CatalogState.ALIVE,
					SingleOwnerPartitionFixture::setUpWithReflectedProducts, List.of()
				);
			}
			// going live is deferred like any other round; the clean close publishes it, so the history is the tail
			engine.close();
			final Evita reopened = start(configurationOf(paths, null, deferred));
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				for (final Consumer<EvitaSessionContract> step : HISTORY) {
					reopened.updateCatalog(catalogName, step);
				}
			}
			final TestPaths image = allocatePaths("walTailImage");
			final Map<String, Long> appliedVersions = takeCrashImage(reopened, paths, image);
			reopened.close();

			final EvitaConfiguration imageConfiguration = configurationOf(image, null, deferred);
			final long imageVersion = readImageVersion(imageConfiguration, OBJECTS_CATALOG);
			assertTrue(
				appliedVersions.get(OBJECTS_CATALOG) > imageVersion,
				"the history must be in the write-ahead log only, or the case proves nothing"
			);
			assertTrue(convertOffline(imageConfiguration, OBJECTS_CATALOG, new RecordingObserver(false)));
			assertEquals(
				imageVersion, readImageVersion(imageConfiguration, OBJECTS_CATALOG),
				"the conversion publishes the version it converted, not a later one"
			);

			final Evita recovered = start(imageConfiguration);
			assertEquals(
				appliedVersions.get(OBJECTS_CATALOG), catalogOf(recovered, OBJECTS_CATALOG).getVersion(),
				"the load must have replayed the whole tail onto the converted catalog"
			);
			assertEquals(
				appliedVersions.get(VIRTUAL_CATALOG), catalogOf(recovered, VIRTUAL_CATALOG).getVersion(),
				"the twin must have replayed its whole tail"
			);
			assertConvertedLikeTheTwin("after the replay of the tail", recovered);
		}
	}

	/**
	 * The memory the conversion holds while it runs.
	 */
	@Nested
	@DisplayName("The conversion holds one partition, one registry and a bounded number of unflushed records")
	class Memory {
		/**
		 * How many owners reference a target of their own - enough single-owner partitions for the registry to be
		 * stored in several leaf pages.
		 */
		private static final int OWNER_COUNT = 150;
		/**
		 * The flush threshold of the case - small enough for the conversion to flush a collection many times.
		 */
		private static final int FLUSH_THRESHOLD = 8;

		@Test
		@DisplayName("each checkpoint holds one partition, one registry and at most the threshold of unflushed records")
		void shouldHoldOnePartitionOneRegistryAndBoundedPendingRecords() {
			final TestPaths paths = allocatePaths("memory");
			final EvitaConfiguration configuration = configurationOf(paths, null, null);
			final Evita engine = start(configuration);
			for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
				buildCatalog(
					engine, catalogName, VIRTUAL_CATALOG.equals(catalogName), CatalogState.ALIVE,
					session -> setUpOwners(session, OWNER_COUNT, 0, false), List.of()
				);
			}
			engine.close();

			final RecordingObserver observer = new RecordingObserver(false);
			assertTrue(convertOffline(configuration, OBJECTS_CATALOG, observer, FLUSH_THRESHOLD));

			final CollectionConversion owners = observer.conversionOf(ENTITY_OWNER);
			assertEquals(OWNER_COUNT, owners.convertedPartitions(), owners.toString());
			assertTrue(
				owners.intermediateFlushes() >= 2,
				"the collection must have been flushed during the conversion, or the bound is vacuous: " + owners
			);
			assertTrue(observer.checkpointCount > OWNER_COUNT, "a checkpoint must follow every partition");
			assertEquals(1, observer.maxHeldPartitions, "partitions held at once");
			assertEquals(1, observer.maxHeldRegistries, "registries held at once");
			assertTrue(
				observer.maxPendingRecords <= FLUSH_THRESHOLD,
				"records written since the last flush: " + observer.maxPendingRecords
			);
			assertTrue(
				observer.maxNonFlushedRecords <= FLUSH_THRESHOLD,
				"unflushed records the data store holds: " + observer.maxNonFlushedRecords
			);
			assertEquals(
				new HashSet<>(observer.partitionReads).size(), observer.partitionReads.size(),
				"every partition must be read once: " + observer.partitionReads
			);
			assertEquals(
				owners.convertedPartitions() + owners.sharedPartitions(),
				observer.partitionReads.stream().filter(read -> read.startsWith(ENTITY_OWNER + "/")).count()
			);
			assertEquals(
				new HashSet<>(observer.registries).size(), observer.registries.size(),
				"every registry must be loaded once: " + observer.registries
			);

			final Evita restarted = start(configuration);
			assertConvertedLikeTheTwin("after the conversion", restarted);
		}
	}

	/**
	 * Defines owners whose reference to targets is indexed for filtering in both scopes and carries a sortable
	 * attribute - optionally reflected on the targets, with everything but the attributes inherited, the indexing
	 * included - and creates the owners, each referencing a target of its own, the first ones the shared target too.
	 * With a reflection, every second target referenced by a single owner is archived, so the reflection has partitions
	 * in both scopes - the stored declaration of an inherited indexing names only the default scope.
	 *
	 * @param session           the session to write in
	 * @param ownerCount        how many owners to create
	 * @param sharingOwnerCount how many of them reference the shared target too
	 * @param reflected         TRUE to reflect the reference on the targets
	 */
	private static void setUpOwners(
		@Nonnull EvitaSessionContract session,
		int ownerCount,
		int sharingOwnerCount,
		boolean reflected
	) {
		session.defineEntitySchema(ENTITY_TARGET).updateVia(session);
		session.defineEntitySchema(ENTITY_OWNER)
			.withReferenceToEntity(
				REFERENCE_TARGETS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					.withAttribute(
						ATTRIBUTE_RANK, Integer.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
					)
			)
			.updateVia(session);
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
					REFERENCE_TARGETS, ownerPrimaryKey, ref -> ref.setAttribute(ATTRIBUTE_RANK, ownerPrimaryKey)
				);
			if (owner <= sharingOwnerCount) {
				builder.setReference(
					REFERENCE_TARGETS, SHARED_TARGET, ref -> ref.setAttribute(ATTRIBUTE_RANK, -ownerPrimaryKey)
				);
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
	 * Builds one catalog of the engine: defines it in the representation asked for, writes the fixture, takes it live
	 * when asked to, and runs the history, every step in a session of its own.
	 *
	 * @param engine      the engine
	 * @param catalogName the catalog
	 * @param virtual     TRUE for a catalog representing its single-owner partitions virtually
	 * @param state       the state the catalog ends in
	 * @param setUp       the fixture
	 * @param history     the steps after the fixture
	 */
	private static void buildCatalog(
		@Nonnull Evita engine,
		@Nonnull String catalogName,
		boolean virtual,
		@Nonnull CatalogState state,
		@Nonnull Consumer<EvitaSessionContract> setUp,
		@Nonnull List<Consumer<EvitaSessionContract>> history
	) {
		// TODO JNO - remove at the end of #1615 (a catalog of index objects is then built by the 2026.2 release only)
		engine.createCatalogWithVirtualReferencePartitions(catalogName, virtual);
		engine.defineCatalog(catalogName).updateViaNewSession(engine);
		engine.updateCatalog(catalogName, setUp);
		if (state == CatalogState.ALIVE) {
			engine.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
		}
		for (final Consumer<EvitaSessionContract> step : history) {
			engine.updateCatalog(catalogName, step);
		}
	}

	/**
	 * Asserts that both catalogs built by the same history answer every query line alike.
	 *
	 * @param engine the engine
	 * @param label  names the moment in the failure message
	 */
	private static void assertTwinsAnswerAlike(@Nonnull Evita engine, @Nonnull String label) {
		assertUnchanged(
			label + ": the catalog of index objects answering as its virtual twin",
			DifferentialEngines.describeQueriesOf(engine, VIRTUAL_CATALOG),
			DifferentialEngines.describeQueriesOf(engine, OBJECTS_CATALOG)
		);
	}

	/**
	 * Asserts that the converted catalog represents its single-owner partitions virtually and is indistinguishable
	 * from its twin - see the class documentation.
	 *
	 * @param label  names the moment in failure messages
	 * @param engine the engine hosting both catalogs
	 */
	private static void assertConvertedLikeTheTwin(@Nonnull String label, @Nonnull Evita engine) {
		assertRepresentation(engine, OBJECTS_CATALOG, true);
		assertUnchanged(
			label + ": the indexes of the converted catalog against its virtual twin",
			describeIndexes(engine, VIRTUAL_CATALOG), describeIndexes(engine, OBJECTS_CATALOG)
		);
		assertUnchanged(
			label + ": the query lines of the converted catalog against its virtual twin",
			DifferentialEngines.describeQueriesOf(engine, VIRTUAL_CATALOG),
			DifferentialEngines.describeQueriesOf(engine, OBJECTS_CATALOG)
		);
		assertInvariants(label, engine, OBJECTS_CATALOG);
	}

	/**
	 * Asserts the representation of a loaded catalog: the storage protocol of its header and its switch holder.
	 *
	 * @param engine      the engine
	 * @param catalogName the catalog
	 * @param virtual     TRUE when the catalog must be virtual
	 */
	private static void assertRepresentation(@Nonnull Evita engine, @Nonnull String catalogName, boolean virtual) {
		final Catalog catalog = catalogOf(engine, catalogName);
		// through the wildcard: `Catalog` holds the service under the interface's parameterisation
		final CatalogPersistenceService<?, ?, ?> persistenceService = catalog.getPersistenceService();
		final DefaultCatalogPersistenceService defaultPersistenceService =
			(DefaultCatalogPersistenceService) persistenceService;
		assertEquals(
			virtual ?
				PersistenceService.VIRTUAL_REFERENCE_PARTITIONS_STORAGE_PROTOCOL_VERSION :
				PersistenceService.STORAGE_PROTOCOL_VERSION,
			defaultPersistenceService.getCatalogHeader(defaultPersistenceService.getLastCatalogVersion())
				.storageProtocolVersion(),
			"the storage protocol of `" + catalogName + "`"
		);
		assertEquals(
			virtual, catalog.getVirtualReferencePartitionMaintenance().isVirtual(),
			"the representation of `" + catalogName + "`"
		);
	}

	/**
	 * Asserts that the conversion left no partition as an index object it does not keep by design - the fixtures hold
	 * none, so a leftover is a misclassified partition.
	 *
	 * @param observer the observer of the conversion
	 */
	private static void assertNoLeftovers(@Nonnull RecordingObserver observer) {
		for (final CollectionConversion conversion : observer.conversions) {
			assertTrue(conversion.leftovers().isEmpty(), conversion.toString());
		}
	}

	/**
	 * Asserts that the conversion wrote into the files of the catalog by appending only: every file is still there,
	 * no file was added - no new data file generation - and every file starts with exactly the bytes it held before.
	 *
	 * @param before            the files before the conversion
	 * @param after             the files after it
	 * @param bootstrapUnchanged TRUE when the bootstrap file must keep its bytes - nothing was published; FALSE when
	 *                           the conversion published, which may rewrite the bootstrap file as a whole (without
	 *                           time travel the store keeps only the newest record), so it is not compared
	 */
	private static void assertOnlyAppended(
		@Nonnull Map<String, StoredFile> before,
		@Nonnull Map<String, StoredFile> after,
		boolean bootstrapUnchanged
	) {
		assertEquals(before.keySet(), after.keySet(), "the conversion must neither add nor remove a file");
		for (final Entry<String, StoredFile> file : before.entrySet()) {
			final StoredFile afterFile = after.get(file.getKey());
			final boolean bootstrapFile = file.getKey().endsWith(PersistenceService.BOOT_FILE_SUFFIX);
			if (bootstrapFile && !bootstrapUnchanged) {
				continue;
			}
			assertTrue(
				afterFile.size() >= file.getValue().size(),
				"file `" + file.getKey() + "` must not have shrunk: " + file.getValue() + " -> " + afterFile
			);
			assertEquals(
				file.getValue().digest(), StoredFile.digestOf(afterFile.path(), file.getValue().size()),
				"file `" + file.getKey() + "` must keep the bytes it held before the conversion"
			);
			if (bootstrapFile) {
				assertEquals(
					file.getValue(), afterFile, "nothing may be published: the bootstrap file must not change"
				);
			}
		}
	}

	/**
	 * Asserts that a compacting flush would have compacted at least one data store the conversion wrote - so the
	 * thresholds of the crash case are low enough to show a compaction had the conversion run one.
	 *
	 * @param configuration the configuration of the engine
	 * @param storage       the storage options of the case
	 * @param observer      the observer of the conversion
	 */
	private void assertCompactionWouldHaveRun(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull StorageOptions storage,
		@Nonnull RecordingObserver observer
	) {
		final List<String> eligible = withOfflineCatalog(
			configuration, OBJECTS_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final List<String> result = new ArrayList<>(2);
				for (final CollectionFileReference reference :
					service.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes()) {
					if (observer.conversionOf(reference.entityType()).changed()) {
						final EntityCollectionFileHeader header = Objects.requireNonNull(
							service.getStoragePartPersistenceService(catalogVersion).getStoragePart(
								catalogVersion, reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class
							)
						);
						final Path catalogDirectory = EvitaTestSupport.catalogDirectory(
							configuration.storage().storageDirectory(), OBJECTS_CATALOG
						);
						final long fileSize = reference.toFilePath(catalogDirectory).toFile().length();
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
			"a data store the conversion wrote must be one a compacting flush would compact, or the case cannot " +
				"show that the conversion compacts none"
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
		for (final String catalogName : new String[]{OBJECTS_CATALOG, VIRTUAL_CATALOG}) {
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
		copyRecursively(paths.storage(), image.storage());
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
	 * Copies a directory tree, keeping the modification times.
	 *
	 * @param source the directory to copy
	 * @param target the directory receiving the copy
	 */
	private static void copyRecursively(@Nonnull Path source, @Nonnull Path target) {
		try (Stream<Path> walk = Files.walk(source)) {
			for (final Path path : walk.toList()) {
				final Path copy = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(copy);
				} else {
					Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Reads the catalog version of the last bootstrap record of a catalog that is not loaded - the version its next
	 * load starts from.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @return the catalog version the last bootstrap record publishes
	 */
	private static long readImageVersion(@Nonnull EvitaConfiguration configuration, @Nonnull String catalogName) {
		return readLastBootstrap(configuration, catalogName).catalogVersion();
	}

	/**
	 * Reads the last bootstrap record of a catalog that is not loaded - the state the catalog has published.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @return the last bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap readLastBootstrap(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName
	) {
		try (
			Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				catalogName,
				EvitaTestSupport.catalogDirectory(configuration.storage().storageDirectory(), catalogName),
				new StorageSettings(configuration.storage(), configuration.transaction()).modifyForBootstrapFile()
			)
		) {
			return records.reduce((previous, next) -> next).orElseThrow();
		}
	}

	/**
	 * Reads the schema of a collection as it is stored, before the reflected references are bound.
	 *
	 * @param service    the persistence service of the catalog, opened without the engine
	 * @param entityType the entity type
	 * @return the stored schema
	 */
	@Nonnull
	private static EntitySchema readStoredSchema(
		@Nonnull DefaultCatalogPersistenceService service,
		@Nonnull String entityType
	) {
		final long catalogVersion = service.getLastCatalogVersion();
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
			service.getCatalogHeader(catalogVersion);
		final CollectionFileReference reference = header.getEntityTypeFileIndexes()
			.stream()
			.filter(it -> it.entityType().equals(entityType))
			.findFirst()
			.orElseThrow();
		return Objects.requireNonNull(
			service.getOrCreateEntityCollectionPersistenceService(
				catalogVersion, entityType, reference.entityTypePrimaryKey()
			).getStoragePartPersistenceService().getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class)
		).entitySchema();
	}

	/**
	 * Describes every file of a catalog folder: its length and the digest of its content.
	 *
	 * @param paths       the directories of the engine
	 * @param catalogName the catalog
	 * @return the files by name
	 */
	@Nonnull
	private static Map<String, StoredFile> storedFiles(@Nonnull TestPaths paths, @Nonnull String catalogName) {
		final Map<String, StoredFile> files = new TreeMap<>();
		try (Stream<Path> list = Files.list(EvitaTestSupport.catalogDirectory(paths.storage(), catalogName))) {
			for (final Path file : list.filter(Files::isRegularFile).toList()) {
				files.put(file.getFileName().toString(), StoredFile.of(file));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return files;
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
	 * Converts a catalog offline with the default flush threshold - see
	 * {@link #convertOffline(EvitaConfiguration, String, Observer, int)}.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @param observer      observes the conversion
	 * @return TRUE when the catalog was converted, FALSE when it was left untouched
	 */
	private boolean convertOffline(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull Observer observer
	) {
		return convertOffline(
			configuration, catalogName, observer, VirtualReferencePartitionConversion.DEFAULT_FLUSH_THRESHOLD
		);
	}

	/**
	 * Converts a catalog offline - with no engine running - through a persistence service opened for the conversion
	 * alone.
	 *
	 * @param configuration  the configuration of the engine
	 * @param catalogName    the catalog
	 * @param observer       observes the conversion
	 * @param flushThreshold the flush threshold of the conversion
	 * @return TRUE when the catalog was converted, FALSE when it was left untouched
	 */
	private boolean convertOffline(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull Observer observer,
		int flushThreshold
	) {
		if (this.evita != null && this.evita.isActive()) {
			fail("The engine must be closed before a catalog of it is converted offline.");
		}
		this.evita = null;
		return withOfflineCatalog(
			configuration, catalogName, service -> service.convertToVirtualReferencePartitions(observer, flushThreshold)
		);
	}

	/**
	 * Opens the catalog with the engine stopped, through the same persistence service the engine loads it with, runs
	 * `work` on it and closes it again, together with the scheduler and export service it needs.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @param work          the work to run
	 * @return the result of the work
	 */
	@Nonnull
	private static <T> T withOfflineCatalog(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull Function<DefaultCatalogPersistenceService, T> work
	) {
		final Path storageDirectory = configuration.storage().storageDirectory();
		final CatalogFolderId folderId = new CatalogFolderId(
			EvitaTestSupport.catalogDirectory(storageDirectory, catalogName).getFileName().toString()
		);
		final Scheduler scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1));
		try (
			ExportFileService exportService = new ExportFileService(configuration.export(), scheduler);
			DefaultCatalogPersistenceService service = new DefaultCatalogPersistenceService(
				new UnusableCatalog(
					catalogName, CatalogState.ALIVE, folderId, storageDirectory,
					CatalogFolderOperations.unsupported("The test opens the catalog only to convert it."),
					(name, folder, root) -> new IllegalStateException("The test never queries the catalog.")
				),
				catalogName, folderId, configuration.storage(), configuration.transaction(), scheduler,
				exportService
			)
		) {
			return work.apply(service);
		} finally {
			scheduler.shutdownNow();
			try {
				assertTrue(scheduler.awaitTermination(30, TimeUnit.SECONDS), "the offline scheduler did not stop");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
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
	 * The failure the crash case injects between two collections.
	 */
	private static final class InjectedFailure extends RuntimeException {
		@Serial private static final long serialVersionUID = -2213358791874539637L;

		/**
		 * @param entityType the collection converted right before the failure
		 */
		private InjectedFailure(@Nonnull String entityType) {
			super("Injected failure after the conversion of `" + entityType + "`.");
		}
	}

	/**
	 * A file of a catalog folder: its length and the digest of its content.
	 *
	 * @param path   the file
	 * @param size   its length
	 * @param digest the SHA-256 of its content
	 */
	private record StoredFile(@Nonnull Path path, long size, @Nonnull String digest) {

		/**
		 * Describes a file.
		 *
		 * @param file the file
		 * @return the description
		 */
		@Nonnull
		static StoredFile of(@Nonnull Path file) {
			try {
				final long size = Files.size(file);
				return new StoredFile(file, size, digestOf(file, size));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Computes the SHA-256 of the first `length` bytes of the file.
		 *
		 * @param file   the file
		 * @param length how many bytes to digest
		 * @return the digest
		 */
		@Nonnull
		static String digestOf(@Nonnull Path file, long length) {
			try (InputStream input = Files.newInputStream(file)) {
				final MessageDigest digest = MessageDigest.getInstance("SHA-256");
				final byte[] buffer = new byte[8_192];
				long remaining = length;
				while (remaining > 0) {
					final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
					if (read < 0) {
						break;
					}
					digest.update(buffer, 0, read);
					remaining -= read;
				}
				return HexFormat.of().formatHex(digest.digest());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * Records what the conversion reports and keeps the peaks of what it holds, and fails on demand after the first
	 * collection in which it converted a partition.
	 */
	private static final class RecordingObserver implements Observer {
		/**
		 * TRUE to fail after the first collection in which a partition was converted.
		 */
		private final boolean failAfterFirstConvertedCollection;
		/**
		 * The registries loaded, as `entityType/reference/scope`.
		 */
		private final List<String> registries = new ArrayList<>(8);
		/**
		 * The partitions read, as `entityType/primaryKey`.
		 */
		private final List<String> partitionReads = new ArrayList<>(64);
		/**
		 * The collections converted, in order.
		 */
		private final List<CollectionConversion> conversions = new ArrayList<>(4);
		/**
		 * How many checkpoints were reported.
		 */
		private int checkpointCount;
		/**
		 * The most partition index objects held at one checkpoint.
		 */
		private int maxHeldPartitions;
		/**
		 * The most registries held at one checkpoint.
		 */
		private int maxHeldRegistries;
		/**
		 * The most records written since a flush, as the conversion counts them.
		 */
		private int maxPendingRecords;
		/**
		 * The most unflushed records, as the data store itself measures them.
		 */
		private int maxNonFlushedRecords;

		/**
		 * @param failAfterFirstConvertedCollection TRUE to fail after the first collection in which a partition was
		 *                                          converted
		 */
		private RecordingObserver(boolean failAfterFirstConvertedCollection) {
			this.failAfterFirstConvertedCollection = failAfterFirstConvertedCollection;
		}

		@Override
		public void registryLoaded(@Nonnull String entityType, @Nonnull String referenceName, @Nonnull Scope scope) {
			this.registries.add(entityType + "/" + referenceName + "/" + scope);
		}

		@Override
		public void partitionRead(@Nonnull String entityType, int partitionPrimaryKey) {
			this.partitionReads.add(entityType + "/" + partitionPrimaryKey);
		}

		@Override
		public void checkpoint(@Nonnull Checkpoint checkpoint) {
			this.checkpointCount++;
			this.maxHeldPartitions = Math.max(this.maxHeldPartitions, checkpoint.heldPartitions());
			this.maxHeldRegistries = Math.max(this.maxHeldRegistries, checkpoint.heldRegistries());
			this.maxPendingRecords = Math.max(this.maxPendingRecords, checkpoint.pendingRecords());
			// measured by the data store itself, not by the conversion
			this.maxNonFlushedRecords = Math.max(
				this.maxNonFlushedRecords, checkpoint.storage().measureVolatileData().nonFlushedRecordCount()
			);
		}

		@Override
		public void collectionConverted(@Nonnull CollectionConversion conversion) {
			this.conversions.add(conversion);
			if (this.failAfterFirstConvertedCollection && conversion.convertedPartitions() > 0) {
				throw new InjectedFailure(conversion.entityType());
			}
		}

		/**
		 * Returns what the conversion did with a collection.
		 *
		 * @param entityType the entity type
		 * @return the conversion of the collection
		 */
		@Nonnull
		CollectionConversion conversionOf(@Nonnull String entityType) {
			return this.conversions.stream()
				.filter(it -> it.entityType().equals(entityType))
				.findFirst()
				.orElseThrow(() -> new AssertionError("The collection `" + entityType + "` was not converted."));
		}

		/**
		 * Returns TRUE when the conversion reported nothing at all.
		 *
		 * @return TRUE when nothing was observed
		 */
		boolean isEmpty() {
			return this.registries.isEmpty() && this.partitionReads.isEmpty() && this.conversions.isEmpty() &&
				this.checkpointCount == 0;
		}

		@Nonnull
		@Override
		public String toString() {
			return "registries " + this.registries + ", partitions " + this.partitionReads + ", conversions " +
				this.conversions;
		}
	}

}
