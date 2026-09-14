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

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that a catalog name's folder generation counter only ever moves forwards, so that no folder token is
 * ever handed out twice for the same name while the process runs.
 *
 * That is not bookkeeping: a `CatalogFolderId` embeds the generation, so monotonicity is the whole reason the
 * token can identify one *incarnation* of a catalog rather than merely its name. A counter that restarted would
 * let a catalog dropped and recreated under the same name be bound to a token identical to the one the previous
 * incarnation had, and any expectation recorded against the old catalog would then be satisfied by the new one -
 * the substitution such an expectation exists to detect.
 *
 * The counter itself is not observable and deliberately gets no inspection API: the assertions read the folder a
 * recreated catalog is actually bound to, which is the only thing the counter is for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog folder generation sequence monotonicity")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CatalogGenerationSequenceMonotonicityTest implements EvitaTestSupport {
	private TestPaths testPaths;
	private Path storageDirectory;

	@BeforeEach
	void setUp() throws IOException {
		this.testPaths = createTestPaths(CatalogGenerationSequenceMonotonicityTest.class.getSimpleName());
		this.storageDirectory = this.testPaths.storage();
		Files.createDirectories(this.storageDirectory);
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.testPaths);
	}

	@Test
	@DisplayName("should not restart a catalog's generations once its last tombstone is discharged")
	void shouldNotRestartGenerationsOnceTheTombstoneIsDischarged() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();

			evita.defineCatalog("products");
			assertEquals("products_1", boundFolderOf(evita, "products"));

			dropCatalog(evita, "products");
			// The removal commits the tombstone and only *then* deletes the folder, so the confirmation that the
			// folder is gone arrives with no commit left to carry it. Any later engine mutation discharges it —
			// here, creating an unrelated catalog. Discharging the last tombstone of a name is the one moment at
			// which nothing in the durable state refers to it any more, and so the only moment at which retiring
			// its counter would ever have looked safe.
			evita.defineCatalog("orders");

			evita.defineCatalog("products");
			assertEquals(
				"products_2", boundFolderOf(evita, "products"),
				"Nothing references `products` any more, but its counter is kept regardless: restarting it here " +
					"would rebuild `products_1` for a different catalog, and a folder token is what tells one " +
					"incarnation of a name from another."
			);
		}
	}

	@Test
	@DisplayName("should keep counting while a tombstone still names a folder the counter could redraw")
	void shouldKeepCountingWhileATombstoneIsOutstanding() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();

			evita.defineCatalog("products");
			assertEquals("products_1", boundFolderOf(evita, "products"));

			dropCatalog(evita, "products");
			// No mutation in between: the recreation allocates its folder in its own work phase, which runs before
			// the commit that discharges the tombstone — so `products_1` is still under a standing order to be
			// deleted at the moment the number is drawn, and drawing it again would bind the new catalog to a
			// folder something is still instructed to destroy.
			evita.defineCatalog("products");
			assertEquals("products_2", boundFolderOf(evita, "products"));

			// Round two walks the same ground with a tombstone discharged in between, which is what makes the
			// assertion load-bearing rather than a restatement of the one above.
			dropCatalog(evita, "products");
			evita.defineCatalog("products");
			assertEquals(
				"products_3", boundFolderOf(evita, "products"),
				"The counter keeps climbing across any number of drop/recreate rounds."
			);
		}
	}

	/**
	 * Reads the folder token the passed catalog is bound to, through the engine state that is the sole authority
	 * on the mapping — never by joining the catalog's name onto the storage root, which has not named a folder
	 * the engine allocates since generations were introduced.
	 *
	 * @param evita       running engine to ask
	 * @param catalogName name of the catalog to resolve
	 * @return textual form of the folder token
	 */
	@Nonnull
	private static String boundFolderOf(@Nonnull Evita evita, @Nonnull String catalogName) {
		final CatalogFolderId folderId = evita.getEngineState().boundFolderIdFor(catalogName);
		assertNotNull(folderId, "Catalog `" + catalogName + "` is not bound to any folder!");
		return folderId.id();
	}

	/**
	 * Removes a catalog and waits for the removal to complete, so the tombstone and the folder deletion that
	 * follows it have both happened before the test proceeds.
	 *
	 * @param evita       running engine to ask
	 * @param catalogName name of the catalog to remove
	 */
	private static void dropCatalog(@Nonnull Evita evita, @Nonnull String catalogName) {
		evita.deleteCatalogIfExistsWithProgress(catalogName)
			.orElseThrow()
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	@Nonnull
	private Evita bootEvita() {
		return new Evita(
			newTestEvitaConfigurationBuilder(this.testPaths)
				.storage(
					StorageOptions.builder()
						.storageDirectory(this.storageDirectory)
						.workDirectory(this.testPaths.work())
						.build()
				)
				.transaction(
					TransactionOptions.builder()
						.transactionMemoryBufferLimitSizeBytes(1024 << 10)
						.transactionMemoryRegionCount(4)
						.build()
				)
				.build()
		);
	}

}
