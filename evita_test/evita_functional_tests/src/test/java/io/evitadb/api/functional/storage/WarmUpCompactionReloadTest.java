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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a warm-up load which compacts a collection more than once can still be reopened.
 *
 * A collection header is addressed by its file index AND its location inside that file. Compaction copies the live
 * records into a fresh file in the same order, so the header record routinely lands at the very offset and length it
 * held in the file it supersedes - which made a location-only "has anything changed?" test report "unchanged" for
 * precisely the rewrite that changed the index. The persisted header then keeps naming a generation that compaction
 * has already retired, and since that header is what a reload resolves the data file from, the catalog cannot be
 * opened at all.
 *
 * It takes TWO compactions of one collection to show. The first moves the header's location and so publishes
 * normally; only from the second onward does the location repeat and the write get skipped. That is why the defect
 * survived - a test-sized corpus compacts once, if at all, and a real bulk load compacts repeatedly.
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

	/**
	 * Writes a corpus that compacts several times over, with nothing failing anywhere, and reopens the engine on the
	 * same storage.
	 *
	 * `PUBLISHED_ROUNDS` is sized so the collection compacts more than once, which is what the defect needs;
	 * {@link #assertCompactionHappened()} refuses to let the test pass vacuously should the corpus or the thresholds
	 * ever drift to where it stops compacting at all.
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
	 * Returns a configuration whose compaction thresholds are low enough that an ordinary warm-up load compacts
	 * within a handful of session closes, with time travel off - the default, and the configuration in which the
	 * superseded generation is actually unlinked rather than kept for a historical read.
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
