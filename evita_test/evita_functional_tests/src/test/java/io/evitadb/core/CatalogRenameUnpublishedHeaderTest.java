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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers a crash **between the catalog header write and the bootstrap publish** inside
 * `DefaultCatalogPersistenceService#replaceWith` - the one window of a rename that nothing exercised.
 *
 * `replaceWith` writes the renamed catalog's header into the offset index and only then publishes a bootstrap
 * record pointing at it; the engine-state commit that repoints the *name* happens later still. A process killed
 * between the first two therefore leaves a data file carrying a header no bootstrap record refers to, over an
 * engine state and a bootstrap that both still describe the catalog under its former name. The decoupling ADR
 * argues this is safe because nothing has been repointed yet. This test is that argument, executed.
 *
 * **The fixture is a real crash artefact, not a hand-written one.** Every byte here is engine output: the storage
 * directory is snapshotted before the rename, the rename is then performed for real, and everything *except* the
 * catalog data files is rewound. That is exactly what the window leaves behind - the data file is append-only, so
 * the header the rename wrote survives in it as unreferenced tail, while the bootstrap, the engine state and the
 * folder's `.catalogname` label are all still the pre-rename ones.
 *
 * **Why a partial rewind here, when `CatalogRenameCrashReplayTest` insists on restoring the whole directory.**
 * That test rewinds a *completed* commit, so anything it left out would describe a world no crash could produce -
 * a catalog bound to a folder a later step had already deleted. This window sits strictly before the commit:
 * nothing has been deleted, nothing repointed, and the data file's extra tail is precisely the asymmetry a crash
 * there produces. Rewinding the data file too would model a crash *before* the header write, which is a different
 * and considerably less interesting window.
 *
 * **No defect-injection calibration, deliberately, and here is what to check instead.** The other tests in this
 * area are calibrated by reverting the line they guard; this one has no such line, because what it asserts is
 * structural - an offset index reads through the root descriptor its bootstrap record names, so bytes past that
 * descriptor are invisible rather than tolerated. There is nothing to revert that would make the window unsafe
 * without redesigning the write path. What this test would catch is a change that ends that property: a strict
 * "the data file must be exactly as long as the descriptor says" check on load, a retry that trips over the dead
 * header, or a reordering that publishes the bootstrap record before the header it points at. The fixture guards
 * itself with `assertDataFileGrewSince`, so it fails rather than silently passing if the rename stops appending.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Crash between the catalog header write and the bootstrap publish")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class CatalogRenameUnpublishedHeaderTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	private static final String RENAMED_CATALOG = "renamedCatalog";
	private static final String COMMITTED_VALUE = "committed before the crashed rename";
	private static final String CATALOG_FILE_SUFFIX = ".catalog";

	private TestPaths paths;
	private Evita evita;

	/**
	 * Managed by JUnit, and deliberately outside the directories the engine knows about, so nothing can mistake
	 * the snapshot for a catalog.
	 */
	@TempDir
	private Path snapshotRoot;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogRenameUnpublishedHeaderTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Leaves the catalog under its former name, usable, and renameable afterwards")
	void shouldIgnoreAHeaderNoBootstrapRecordPointsAt() {
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
		defineCatalogAndGoLive();
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);
		this.evita.close();

		// the world as it stands before the rename writes anything at all
		final Path snapshot = captureStorage();

		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
		this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);
		this.evita.close();

		// The whole fixture rests on the rename having appended something to the data file: if it wrote its
		// header anywhere else, rewinding around that file models nothing and this test passes for no reason.
		// Asserted rather than assumed, so a change to where the header goes fails here instead of quietly
		// turning the crash into a no-op.
		assertDataFileGrewSince(snapshot);

		// rewind everything the crash would not have reached, and keep the data file the rename appended its
		// header to - the header is in there, and no bootstrap record names it any more
		rewindEverythingButTheCatalogData(snapshot);

		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();

		// nothing was repointed before the crash, so the catalog has to answer to the name it had all along
		assertTrue(
			this.evita.getCatalogNames().contains(TEST_CATALOG),
			"A crash before the bootstrap publish must leave the catalog under its former name!"
		);
		assertFalse(
			this.evita.getCatalogNames().contains(RENAMED_CATALOG),
			"The rename never became durable, so its name must not be known to the engine!"
		);
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"The unpublished header must not cost the catalog its data!"
		);

		// and the failed attempt must not poison the next one - the dead header sits in the same file the retry
		// appends to
		this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);
		assertEquals(
			COMMITTED_VALUE, readPayload(RENAMED_CATALOG, 1),
			"A rename retried after the crash must succeed and keep the data!"
		);

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
		assertEquals(
			COMMITTED_VALUE, readPayload(RENAMED_CATALOG, 1),
			"The retried rename must survive a restart of its own!"
		);
	}

	/**
	 * Asserts the rename appended to the catalog data file - the unreferenced header whose survival across the
	 * rewind is the entire point of the fixture.
	 *
	 * @param snapshot the pre-rename snapshot to compare against
	 */
	private void assertDataFileGrewSince(@Nonnull Path snapshot) {
		final long before = soleCatalogDataFileSize(snapshot);
		final long after = soleCatalogDataFileSize(this.paths.storage());
		assertTrue(
			after > before,
			() -> "The rename must have appended its header to the catalog data file, but the file went from " +
				before + " B to " + after + " B - the fixture would model nothing."
		);
	}

	/**
	 * Returns the size of the single catalog data file found anywhere under the passed directory.
	 *
	 * @param root directory to search
	 * @return size of the data file in bytes
	 */
	private static long soleCatalogDataFileSize(@Nonnull Path root) {
		try (final Stream<Path> tree = Files.walk(root)) {
			final List<Path> dataFiles = tree
				.filter(path -> path.getFileName().toString().endsWith(CATALOG_FILE_SUFFIX))
				.toList();
			assertEquals(1, dataFiles.size(), () -> "Exactly one catalog data file is expected under " + root + "!");
			return Files.size(dataFiles.get(0));
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot measure the catalog data file under `" + root + "`!", ex);
		}
	}

	/**
	 * Copies the whole storage directory aside. The engine must be closed.
	 *
	 * @return the directory the snapshot was written to
	 */
	@Nonnull
	private Path captureStorage() {
		final Path snapshot = this.snapshotRoot.resolve("storage-" + UUID.randomUUID());
		copyTree(this.paths.storage(), snapshot, false);
		return snapshot;
	}

	/**
	 * Puts the snapshot back over the live storage directory, leaving every catalog data file as the rename left
	 * it. The engine must be closed.
	 *
	 * @param snapshot directory to rewind from
	 */
	private void rewindEverythingButTheCatalogData(@Nonnull Path snapshot) {
		copyTree(snapshot, this.paths.storage(), true);
	}

	/**
	 * Copies a directory tree over another, optionally leaving catalog data files in the target untouched.
	 * Deliberately plain: the trees are a handful of small files, and a fixture that needs explaining is a
	 * fixture that gets doubted.
	 *
	 * @param source        tree to copy from
	 * @param target        tree to copy into
	 * @param keepDataFiles when true, `*.catalog` files already present in the target are left alone
	 */
	private static void copyTree(@Nonnull Path source, @Nonnull Path target, boolean keepDataFiles) {
		try {
			final List<Path> entries;
			try (final Stream<Path> tree = Files.walk(source)) {
				entries = tree.toList();
			}
			for (final Path entry : entries) {
				final Path destination = target.resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else if (!keepDataFiles || !entry.getFileName().toString().endsWith(CATALOG_FILE_SUFFIX)) {
					Files.createDirectories(destination.getParent());
					Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy `" + source + "` to `" + target + "`!", ex);
		}
	}

	/**
	 * Commits a single product in its own transaction and waits until the change is visible.
	 *
	 * @param catalogName    catalog to write into
	 * @param primaryKey     primary key of the product
	 * @param attributeValue value stored in the payload attribute
	 */
	private void commitProduct(@Nonnull String catalogName, int primaryKey, @Nonnull String attributeValue) {
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(catalogName, SessionFlags.READ_WRITE));
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, primaryKey)
			       .setAttribute(ATTRIBUTE_PAYLOAD, attributeValue)
		);
		assertNotNull(session.closeNowWithProgress().onChangesVisible().toCompletableFuture().join());
	}

	/**
	 * Reads a single product's payload attribute back through a fresh session.
	 *
	 * @param catalogName catalog to read from
	 * @param primaryKey  primary key of the product
	 * @return the stored payload
	 */
	@Nonnull
	private String readPayload(@Nonnull String catalogName, int primaryKey) {
		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return session.getEntity(Entities.PRODUCT, primaryKey, attributeContentAll())
					.orElseThrow(() -> new AssertionError("The product must be present!"))
					.getAttribute(ATTRIBUTE_PAYLOAD, String.class);
			}
		);
	}

	/**
	 * Defines the catalog with a single product schema and takes it live.
	 */
	private void defineCatalogAndGoLive() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withoutGeneratedPrimaryKey()
				       .withAttribute(ATTRIBUTE_PAYLOAD, String.class)
				       .updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Stock storage options - nothing here depends on the log or on time travel.
	 *
	 * @return the configuration, never null
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
