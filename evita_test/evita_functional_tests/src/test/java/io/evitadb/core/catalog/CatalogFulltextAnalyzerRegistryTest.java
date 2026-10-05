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

package io.evitadb.core.catalog;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a catalog owns one {@link FulltextAnalyzerRegistry} for its whole lifetime: every rebuild of the
 * catalog object - going live, a commit, a rename - hands the same registry on and leaves it open, the catalog's
 * termination closes it, and a catalog loaded from disk reads its fulltext indexes back through a registry of its own.
 *
 * `CatalogUsageRegistryTest` pins the same carry-over discipline for the usage registry, which travels through the
 * same copy sites.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog analyzer registry")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(FULLTEXT)
class CatalogFulltextAnalyzerRegistryTest implements EvitaTestSupport {
	private static final String CATALOG = "catalogFulltextAnalyzerRegistryTest";
	private static final String RENAMED_CATALOG = CATALOG + "Renamed";
	private static final String ENTITY_PRODUCT = "product";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	/** The message every identity assertion fails with. */
	private static final String REGISTRY_LOST =
		"The rebuilt catalog carries an analyzer registry of its own, so every catalog rebuilt this way builds every " +
			"analyzer chain again and leaves the previous registry to nobody - pass it through this copy site: ";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogFulltextAnalyzerRegistryTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Registry survives the catalog rebuild")
	class SurvivesRebuild {

		@Test
		@DisplayName("Going live")
		void shouldCarryTheRegistryThroughGoLive() {
			final Catalog before = catalogOf(CATALOG);
			final FulltextAnalyzerRegistry registry = before.getFulltextAnalyzerRegistry();

			goLive();

			assertRegistryCarriedOver(registry, before, CATALOG, "going live");
		}

		@Test
		@DisplayName("A transactional write advancing the catalog version")
		void shouldCarryTheRegistryThroughACommit() {
			goLive();
			final Catalog before = catalogOf(CATALOG);
			final FulltextAnalyzerRegistry registry = before.getFulltextAnalyzerRegistry();

			upsertProduct(1);

			assertRegistryCarriedOver(registry, before, CATALOG, "the commit-time catalog version advance");
		}

		@Test
		@DisplayName("Renaming the catalog")
		void shouldCarryTheRegistryThroughACatalogRename() {
			final Catalog before = catalogOf(CATALOG);
			final FulltextAnalyzerRegistry registry = before.getFulltextAnalyzerRegistry();

			CatalogFulltextAnalyzerRegistryTest.this.evita.renameCatalog(CATALOG, RENAMED_CATALOG);
			// the rename closes the persistence service of the instance it replaces, so terminating that instance
			// afterwards is a no-op - which is what keeps the shared registry open for the renamed catalog
			before.terminate();

			assertRegistryCarriedOver(registry, before, RENAMED_CATALOG, "the catalog rename");
		}

	}

	@Nested
	@DisplayName("Registry lifetime ends with the catalog")
	class Lifetime {

		@Test
		@DisplayName("Closing the engine closes the registry")
		void shouldCloseTheRegistryWhenTheEngineCloses() {
			final FulltextAnalyzerRegistry registry = catalogOf(CATALOG).getFulltextAnalyzerRegistry();
			registry.getIndexAnalyzer(ENTITY_PRODUCT, CZECH);

			CatalogFulltextAnalyzerRegistryTest.this.evita.close();

			assertClosed(registry, "closing the engine");
		}

		@Test
		@DisplayName("Deleting the catalog closes the registry")
		void shouldCloseTheRegistryWhenTheCatalogIsDeleted() {
			final FulltextAnalyzerRegistry registry = catalogOf(CATALOG).getFulltextAnalyzerRegistry();
			registry.getIndexAnalyzer(ENTITY_PRODUCT, CZECH);

			CatalogFulltextAnalyzerRegistryTest.this.evita.deleteCatalogIfExists(CATALOG);

			assertClosed(registry, "deleting the catalog");
		}

		@Test
		@DisplayName("A restart reads the fulltext indexes back through a fresh registry")
		void shouldReloadTheFulltextIndexThroughAFreshRegistry() {
			final Catalog before = catalogOf(CATALOG);
			final FulltextAnalyzerRegistry registry = before.getFulltextAnalyzerRegistry();
			// no schema can make the write path fill a fulltext index yet, so the index is filled directly - what is
			// under test is that the catalog load hands its registry down to the fulltext loader. It is filled inside
			// the session of an upsert, after it: the upsert is what registers the global index as modified, and a
			// warm-up flush persists only the indexes registered that way
			final FulltextIndex[] filled = new FulltextIndex[1];
			CatalogFulltextAnalyzerRegistryTest.this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 1));
					filled[0] = globalIndexOf(before).getOrCreateFulltextIndex(
						CZECH, registry.getIndexAnalyzer(ENTITY_PRODUCT, CZECH)
					);
					filled[0].addValue(attribute("name"), 1, "zelený čaj");
				}
			);
			final FulltextIndex index = filled[0];
			final int fieldId = index.getFieldId(attribute("name"));
			final int termCount = index.getTermCount();

			restart();

			final Catalog after = catalogOf(CATALOG);
			assertNotSame(
				registry, after.getFulltextAnalyzerRegistry(), "A reloaded catalog must mint its own registry"
			);
			assertClosed(registry, "the restart");
			final FulltextIndex reloaded = globalIndexOf(after).getFulltextIndex(CZECH);
			assertEquals(index.getAnalyzerName(), reloaded.getAnalyzerName());
			assertEquals(termCount, reloaded.getTermCount());
			assertEquals(fieldId, reloaded.getFieldId(attribute("name")));
		}

	}

	/**
	 * Asserts that a rebuild really happened, that the rebuilt catalog holds the very same registry, and that the
	 * registry is still open.
	 *
	 * @param registry the registry the catalog held before the rebuild
	 * @param before   the catalog instance that held it
	 * @param catalog  name the rebuilt catalog is published under
	 * @param site     what rebuilt it, named the way the failure message should read
	 */
	private void assertRegistryCarriedOver(
		@Nonnull FulltextAnalyzerRegistry registry,
		@Nonnull Catalog before,
		@Nonnull String catalog,
		@Nonnull String site
	) {
		final Catalog after = catalogOf(catalog);
		assertNotSame(
			before, after,
			"`" + site + "` did not rebuild the catalog at all, so this test proves nothing - find the operation " +
				"that reaches the copy site again"
		);
		assertSame(registry, after.getFulltextAnalyzerRegistry(), REGISTRY_LOST + site);
		// still open: the superseded instance must not have released what the rebuilt one goes on using
		assertEquals("czech", registry.getIndexAnalyzer(ENTITY_PRODUCT, CZECH).getAnalyzerName());
	}

	/**
	 * Asserts that the registry refuses every lookup, which is what a closed registry does.
	 *
	 * @param registry the registry expected to be closed
	 * @param site     what should have closed it, named the way the failure message should read
	 */
	private static void assertClosed(@Nonnull FulltextAnalyzerRegistry registry, @Nonnull String site) {
		final EvitaInvalidUsageException error = assertThrows(
			EvitaInvalidUsageException.class,
			() -> registry.getIndexAnalyzer(ENTITY_PRODUCT, CZECH),
			"`" + site + "` left the catalog's analyzer registry open, so its analyzers keep their per-thread " +
				"stream components reachable for the lifetime of the process"
		);
		assertTrue(error.getMessage().contains("closed"), error.getMessage());
	}

	/**
	 * Returns the catalog instance currently published under the name.
	 *
	 * @param catalogName name of the catalog
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalogOf(@Nonnull String catalogName) {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(catalogName);
	}

	/**
	 * Returns the global index of the product collection of the passed catalog.
	 *
	 * @param catalog the catalog
	 * @return the global index
	 */
	@Nonnull
	private static GlobalEntityIndex globalIndexOf(@Nonnull Catalog catalog) {
		return catalog.getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow(() -> new AssertionError("The test catalog holds no `" + ENTITY_PRODUCT + "` collection"))
			.getGlobalIndex();
	}

	/**
	 * Takes the catalog live, so what follows commits transactionally and rebuilds the catalog on every write.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Writes one product.
	 *
	 * @param primaryKey primary key of the product to write
	 */
	private void upsertProduct(int primaryKey) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, primaryKey));
			}
		);
	}

	/**
	 * Closes the embedded instance and opens a new one over the same directories, so what follows reads state that was
	 * rebuilt from disk rather than state still held in memory.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/**
	 * Builds the configuration of the embedded instance this test runs against.
	 *
	 * @return configuration rooted at this test's directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
