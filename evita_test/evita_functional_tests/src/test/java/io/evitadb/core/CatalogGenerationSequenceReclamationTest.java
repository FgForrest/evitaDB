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
 * Verifies that the engine-scoped folder generation counters are reclaimed when the catalog names they belong to
 * stop being referenced, and — just as importantly — that they are *not* reclaimed while a tombstone still names a
 * folder the counter could hand out again.
 *
 * The counter itself is not observable and deliberately gets no inspection API: the assertions read the folder a
 * recreated catalog is actually bound to, which is the only thing the counter is for. That also keeps the test on
 * the production path — `SequenceService#removeSequences` is covered directly by `SequenceServiceTest`, and a test
 * that called it by hand here would prove nothing about whether anything calls it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog folder generation sequence reclamation")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CatalogGenerationSequenceReclamationTest implements EvitaTestSupport {
	private TestPaths testPaths;
	private Path storageDirectory;

	@BeforeEach
	void setUp() throws IOException {
		this.testPaths = createTestPaths(CatalogGenerationSequenceReclamationTest.class.getSimpleName());
		this.storageDirectory = this.testPaths.storage();
		Files.createDirectories(this.storageDirectory);
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.testPaths);
	}

	@Test
	@DisplayName("should restart a catalog's generations once its last tombstone is discharged")
	void shouldRestartGenerationsOnceTheTombstoneIsDischarged() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();

			evita.defineCatalog("products");
			assertEquals("products_1", boundFolderOf(evita, "products"));

			dropCatalog(evita, "products");
			// The removal commits the tombstone and only *then* deletes the folder, so the confirmation that the
			// folder is gone arrives with no commit left to carry it. Any later engine mutation discharges it —
			// here, creating an unrelated catalog — and that is the commit at which the counter may be retired.
			evita.defineCatalog("orders");

			evita.defineCatalog("products");
			assertEquals(
				"products_1", boundFolderOf(evita, "products"),
				"Nothing references `products` any more, so its counter went with the tombstone and the name " +
					"starts from its first generation again."
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

			// The commit that just discharged `products_1` also nominated `products` for retirement, and the live
			// binding it had just recorded is the only thing that refused it. Round two is what makes that refusal
			// load-bearing: a counter wrongly retired above would restart here at `products_1` instead.
			dropCatalog(evita, "products");
			evita.defineCatalog("products");
			assertEquals(
				"products_3", boundFolderOf(evita, "products"),
				"The counter survived the discharge because the name was bound again in that same commit."
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
