/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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


package io.evitadb.api.functional.storage;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static io.evitadb.test.Entities.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ways a compacting warm-up load can leave a catalog that cannot be opened again.
 *
 * Compaction rewrites a collection into a NEW file and retires the old one, so it is the one operation that both
 * changes which file the catalog must point at and removes the file it used to point at. Each half has its own way of
 * going wrong, and they are independent - the tests below fail for different reasons and against different fixes.
 *
 * **The published header must name the new file.** A collection header is addressed by its file index AND its
 * location inside that file. Compaction copies the live records in the same order, so the header record routinely
 * lands at the very offset and length it held in the file it supersedes - which made a location-only
 * "has anything changed?" test report "unchanged" for precisely the rewrite that changed the index. The persisted
 * header then keeps naming a generation that has been retired, and since that header is what a reload resolves the
 * data file from, the catalog is unloadable. It takes TWO compactions to show: the first moves the location and
 * publishes normally, and only from the second does the location hold still.
 *
 * **A file the published record still names must not be deleted.** Appends are inert - bytes no bootstrap record
 * reaches are dead space - but deletes are not. The retired file stays named by the CURRENTLY published record until
 * the round that supersedes it publishes, so unlinking it inside that round removes a file the pointer chain a reload
 * follows still reaches. In `WARM_UP` the maintainer purges eagerly, having no catalog versions to hold a file
 * against, which is where that used to happen.
 *
 * The third test is about neither: it takes a catalog a pre-fix build already damaged and requires it to open. The
 * file a collection lives in is recorded twice - in the catalog header and on the collection header - and only the
 * first is written unconditionally, so the load path resolves from that one and reconciles the other against it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(TestTags.STORAGE)
@Tag(TestTags.SESSION)
@DisplayName("A warm-up catalog that compacted must reload from what it published")
class WarmUpCompactionReloadTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	/**
	 * Filler wide enough that a handful of entities crosses the tiny compaction threshold below.
	 */
	private static final String FILLER = "x".repeat(2_048);
	private static final int BATCH_SIZE = 40;
	private static final int PUBLISHED_ROUNDS = 4;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("WarmUpCompactionReload");
		this.evita = new Evita(compactionEagerConfiguration());
	}

	@AfterEach
	@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("A round that fails before publishing leaves the previously published generation on disk")
	void shouldKeepThePublishedGenerationWhenTheSupersedingRoundNeverPublishes() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class)
					.updateVia(session);
			}
		);

		// several published rounds, each rewriting the same entities so the previous generation of their storage
		// parts becomes waste - which is what drives the collection file over the compaction thresholds
		for (int round = 0; round < PUBLISHED_ROUNDS; round++) {
			writeBatch(round);
		}

		// the test is worthless unless compaction actually ran, and a file index above zero is the only observable
		// proof of it - so assert it rather than assume the thresholds did their job
		final Set<Integer> indexesBeforeFailure = collectionFileIndexes();
		assertTrue(
			indexesBeforeFailure.stream().anyMatch(index -> index > 0),
			"The corpus must have compacted at least once, otherwise this test asserts nothing. " +
				"Collection file indexes present: " + indexesBeforeFailure
		);

		// from here on the catalog can never publish again: the round below compacts, then fails at the header write
		injectStoreHeaderFailure();
		assertThrows(
			RuntimeException.class,
			() -> writeBatch(PUBLISHED_ROUNDS),
			"The round whose header write is injected to fail must surface the failure."
		);

		// restart on the same storage - the reload follows the last PUBLISHED bootstrap record, and every file that
		// record names must still be there
		this.evita.close();
		this.evita = new Evita(compactionEagerConfiguration());
		// the catalog is loaded on the service pool, so `new Evita` returns while it is still BEING_ACTIVATED -
		// querying before that settles fails with CatalogTransitioningException and says nothing about the reload
		this.evita.waitUntilFullyInitialized();

		final int entityCount = assertDoesNotThrow(
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, Integer>) session -> session.getEntityCollectionSize(PRODUCT)
			),
			"The catalog must reload from its last published state - the round that failed to publish must not " +
				"have taken the generation that state names."
		);
		assertEquals(
			BATCH_SIZE, entityCount,
			"The reloaded catalog must hold exactly what the last successful flush published."
		);
	}

	/**
	 * Reproduces what a build predating the header-index fix left on disk, and requires the catalog to open anyway.
	 *
	 * The damage is one stale integer: the collection header naming a data file generation that compaction has
	 * already replaced and retired, while the catalog header names the current one. It is manufactured through the
	 * real publication path rather than by writing bytes - the collection header is rewritten to the previous
	 * generation, and the catalog is then made to publish by a change that does not touch `PRODUCT` at all, so the
	 * flush skips the collection header exactly as the old comparison would have and the stale value survives into
	 * the published state.
	 *
	 * Without the reconciliation at load this fails on a file that was correctly deleted; with it the catalog
	 * resolves from the catalog header - the copy written unconditionally - warns, and opens with its data intact.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("A collection header left naming a superseded generation still reloads")
	void shouldReloadWhenTheCollectionHeaderNamesASupersededGeneration() {
		defineProductSchema();
		for (int round = 0; round < PUBLISHED_ROUNDS; round++) {
			writeBatch(round);
		}
		assertCompactionHappened();

		final int supersededIndex = staleTheCollectionHeader();
		publishWithoutTouchingProduct();

		assertFalse(
			collectionFileIndexes().contains(supersededIndex),
			"The generation the collection header now names must be gone from disk, or the test proves nothing - " +
				"it would be resolving a file that happens to still be there."
		);

		this.evita.close();
		final String storageTree = describeStorageTree();
		this.evita = new Evita(compactionEagerConfiguration());
		// the catalog is loaded on the service pool, so `new Evita` returns while it is still BEING_ACTIVATED -
		// querying before that settles fails with CatalogTransitioningException and says nothing about the reload
		this.evita.waitUntilFullyInitialized();

		final int entityCount = assertDoesNotThrow(
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, Integer>) session -> session.getEntityCollectionSize(PRODUCT)
			),
			"A collection header naming a retired generation must be reconciled against the catalog header, which " +
				"names the current one. Storage tree: " + storageTree
		);
		assertEquals(
			BATCH_SIZE, entityCount,
			"Reconciling the file index must not cost any data - the records were never in doubt."
		);
	}

	/**
	 * Rewrites the `PRODUCT` collection header to name the generation before the current one, reproducing what
	 * a skipped header write used to leave behind.
	 *
	 * Only the file index is changed, because that is the only field the historical defect could corrupt: the write
	 * was skipped precisely because the header record's location had not moved.
	 *
	 * @return the file index the collection header now (wrongly) names
	 */
	private int staleTheCollectionHeader() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		try {
			final Field field = Catalog.class.getDeclaredField("persistenceService");
			field.setAccessible(true);
			final DefaultCatalogPersistenceService persistenceService =
				(DefaultCatalogPersistenceService) field.get(catalog);
			final CatalogOffsetIndexStoragePartPersistenceService storagePartService =
				persistenceService.getStoragePartPersistenceService(0L);
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader =
				storagePartService.getCatalogHeader(0L);
			final CollectionFileReference reference = catalogHeader
				.getEntityTypeFileIndexIfExists(PRODUCT)
				.orElseThrow(() -> new IllegalStateException("Catalog header does not know `PRODUCT` yet!"));
			final EntityCollectionFileHeader current = Objects.requireNonNull(
				storagePartService.getStoragePart(
					0L, reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class
				),
				"Collection header for `PRODUCT` is missing!"
			);
			final int supersededIndex = current.entityTypeFileIndex() - 1;
			storagePartService.putStoragePart(0L, current.withEntityTypeFileIndex(supersededIndex));
			return supersededIndex;
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to stale the collection header", ex);
		}
	}

	/**
	 * Publishes a bootstrap record via a change that leaves `PRODUCT` alone.
	 *
	 * This is what carries the staled collection header into the published state. A change to `PRODUCT` would move
	 * its header record and so cause the flush to rewrite the collection header correctly, undoing the damage; a
	 * catalog schema change makes the flush publish while `PRODUCT`'s header still compares equal to the one the
	 * catalog header already holds, which is exactly the condition under which the write is skipped.
	 */
	private void publishWithoutTouchingProduct() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute("recoveryProbe", String.class)
					.updateVia(session);
			}
		);
	}

	/**
	 * The same corpus and the same compaction as the scenario above, but with NO injected failure: every round
	 * publishes, and the catalog must still come back.
	 *
	 * This is the half that catches a published header still naming a superseded generation. It needs the corpus to
	 * compact more than once - the first compaction moves the header's location and so publishes through a
	 * location-only change test, and only from the second one onward does the location repeat and the write get
	 * skipped. `PUBLISHED_ROUNDS` is sized for that, and {@link #assertCompactionHappened()} refuses to let the test
	 * pass vacuously if it ever stops being.
	 *
	 * It also keeps the scenario above honest. If a warm-up catalog that compacted cannot be reloaded even when
	 * nothing failed, then the retirement is not what broke it, and a failure there would be pointing at the wrong
	 * thing entirely - which is exactly what happened while this defect was still open.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("A catalog that compacted repeatedly reloads from its published state")
	void shouldReloadAfterRepeatedCompaction() {
		defineProductSchema();
		for (int round = 0; round < PUBLISHED_ROUNDS; round++) {
			writeBatch(round);
		}
		assertCompactionHappened();

		this.evita.close();
		final String storageTree = describeStorageTree();
		this.evita = new Evita(compactionEagerConfiguration());
		// the catalog is loaded on the service pool, so `new Evita` returns while it is still BEING_ACTIVATED -
		// querying before that settles fails with CatalogTransitioningException and says nothing about the reload
		this.evita.waitUntilFullyInitialized();

		final int entityCount = assertDoesNotThrow(
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, Integer>) session -> session.getEntityCollectionSize(PRODUCT)
			),
			"A warm-up catalog whose rounds all published must reload - nothing here even failed, so a " +
				"failure means the published collection header names a file compaction has already replaced. " +
				"Storage tree: " + storageTree
		);
		assertEquals(
			BATCH_SIZE, entityCount,
			"The reloaded catalog must hold everything the clean load published."
		);
	}

	/**
	 * Defines the `PRODUCT` schema the corpus is written against.
	 */
	private void defineProductSchema() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class)
					.updateVia(session);
			}
		);
	}

	/**
	 * Asserts that the corpus actually compacted, which both tests depend on and neither can observe any other way.
	 *
	 * A file index above zero is the only evidence a compaction leaves behind. Without this check a corpus that
	 * stopped meeting the thresholds would make both tests pass while asserting nothing at all.
	 */
	private void assertCompactionHappened() {
		final Set<Integer> indexes = collectionFileIndexes();
		assertTrue(
			indexes.stream().anyMatch(index -> index > 0),
			"The corpus must have compacted at least once, otherwise this test asserts nothing. " +
				"Collection file indexes present: " + indexes
		);
	}

	/**
	 * Writes the batch inside a single session, so closing it flushes and publishes a round.
	 *
	 * The first round creates the entities; every later round REWRITES the same ones with fresh content. That is what
	 * makes the corpus compact: each rewrite supersedes a wide entity body, and superseded records are exactly the
	 * waste `shouldCompact` measures. Writing fresh keys each round instead grows the file without lowering the live
	 * share, which is how an earlier revision of this test managed to load a quarter of a megabyte and never compact.
	 *
	 * @param round ordinal of the round, mixed into the attribute value so every pass supersedes the previous one
	 */
	private void writeBatch(int round) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= BATCH_SIZE; pk++) {
					final int primaryKey = pk;
					session.getEntity(PRODUCT, primaryKey, attributeContentAll())
						.map(SealedEntity::openForWrite)
						.orElseGet(() -> session.createNewEntity(PRODUCT, primaryKey))
						.setAttribute(ATTRIBUTE_CODE, round + "-" + primaryKey + "-" + FILLER)
						.upsertVia(session);
				}
			}
		);
	}

	/**
	 * Renders every file in the storage tree with its size, for a failure message that says what is actually on
	 * disk rather than leaving the reader to guess.
	 *
	 * A reload failure names one offset in one file; which files exist, and which are empty, is the other half of
	 * the story and nothing else recovers it after the fact.
	 *
	 * @return one `name (size B)` entry per file, sorted, or the reason the tree could not be listed
	 */
	@Nonnull
	private String describeStorageTree() {
		try (Stream<Path> tree = Files.walk(this.paths.storage())) {
			return tree
				.filter(Files::isRegularFile)
				.map(path -> {
					try {
						return this.paths.storage().relativize(path) + " (" + Files.size(path) + " B)";
					} catch (IOException ex) {
						return this.paths.storage().relativize(path) + " (unreadable: " + ex.getMessage() + ")";
					}
				})
				.sorted()
				.collect(Collectors.joining(", "));
		} catch (IOException ex) {
			return "<storage tree could not be listed: " + ex.getMessage() + ">";
		}
	}

	/**
	 * Returns the file indexes of every `PRODUCT` collection data file currently present in the storage tree.
	 *
	 * Reading the directory is exactly what a test may do and production may not: the assertion is about which files
	 * survive on disk, which no engine API exposes.
	 *
	 * @return the set of file indexes found, empty when the collection has not been flushed yet
	 */
	@Nonnull
	private Set<Integer> collectionFileIndexes() {
		final Pattern pattern = Pattern.compile(
			Pattern.quote(StringUtils.toCamelCase(PRODUCT)) + "-\\d+_(\\d+)" +
				Pattern.quote(ENTITY_COLLECTION_FILE_SUFFIX)
		);
		try (Stream<Path> tree = Files.walk(this.paths.storage())) {
			return tree
				.map(path -> pattern.matcher(path.getFileName().toString()))
				.filter(Matcher::matches)
				.map(matcher -> Integer.parseInt(matcher.group(1)))
				.collect(Collectors.toSet());
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to list the catalog storage tree", ex);
		}
	}

	/**
	 * Makes every later `storeHeader` call on the live catalog throw, so the round that follows compacts and then
	 * fails before it can publish a bootstrap record naming the compacted file.
	 */
	private void injectStoreHeaderFailure() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		try {
			final Field field = Catalog.class.getDeclaredField("persistenceService");
			field.setAccessible(true);
			final CatalogPersistenceService<?, ?, ?> real = (CatalogPersistenceService<?, ?, ?>) field.get(catalog);
			final CatalogPersistenceService<?, ?, ?> failing = (CatalogPersistenceService<?, ?, ?>) Proxy.newProxyInstance(
				CatalogPersistenceService.class.getClassLoader(),
				new Class<?>[]{CatalogPersistenceService.class},
				(proxy, method, args) -> {
					if ("storeHeader".equals(method.getName())) {
						throw new UnexpectedIOException(
							"Injected header-write failure after compaction",
							"The catalog header could not be written."
						);
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				}
			);
			field.set(catalog, failing);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to inject the storeHeader failure", ex);
		}
	}

	/**
	 * Returns a configuration whose compaction thresholds are low enough that an ordinary warm-up load compacts
	 * within a handful of session closes, with time travel off - the default, and the configuration in which the
	 * retirement actually unlinks the file.
	 *
	 * @return the configuration both engine instances of this test are built from
	 */
	@Nonnull
	private EvitaConfiguration compactionEagerConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.fileSizeCompactionThresholdBytes(16_384)
					.minimalActiveRecordShare(0.9)
					.minCompactionIntervalMilliseconds(0)
					.timeTravelEnabled(false)
					.build()
			)
			.build();
	}

}
