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

package io.evitadb.store.engine;

import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that folder allocation survives a name it cannot use.
 *
 * The interesting behaviour is not the happy path but the retry: an allocator that drew one number and gave up
 * would turn a folder the filesystem refuses to recreate into a permanent wedge, failing identically after every
 * restart, and the failure would read as an ordinary environment problem rather than as a livelock.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(MANAGEMENT)
@DisplayName("Catalog folder allocation")
class CatalogFolderAllocatorTest {

	/**
	 * Returns a supplier handing out 1, 2, 3 … — the shape the engine-scoped sequence has.
	 *
	 * @return an ascending generation supplier
	 */
	@Nonnull
	private static IntSupplier ascendingGenerations() {
		final AtomicInteger sequence = new AtomicInteger();
		return sequence::incrementAndGet;
	}

	/**
	 * Creates a directory under the storage root, as if an earlier attempt had left it behind.
	 *
	 * @param storageDirectory the storage root
	 * @param folderName       name of the directory to create
	 * @param fileNames        files to place inside it
	 */
	private static void occupy(
		@Nonnull Path storageDirectory,
		@Nonnull String folderName,
		@Nonnull String... fileNames
	) {
		try {
			final Path folder = Files.createDirectory(storageDirectory.resolve(folderName));
			for (final String fileName : fileNames) {
				Files.createFile(folder.resolve(fileName));
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Nested
	@DisplayName("Allocating a free name")
	class HappyPath {

		@Test
		@DisplayName("Creates the folder the returned token names")
		void shouldCreateFolderNamedAfterCatalogAndGeneration(@TempDir Path storageDirectory) {
			final CatalogFolderId folderId = CatalogFolderAllocator.allocate(
				storageDirectory, "products", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("products_1"), folderId);
			assertTrue(Files.isDirectory(storageDirectory.resolve("products_1")));
		}

		@Test
		@DisplayName("Marks the fresh folder provisional straight away")
		void shouldWriteProvisionalMarkerIntoFreshFolder(@TempDir Path storageDirectory) {
			// everything the engine creates is marked from the instant it exists, so a crash can never leave a
			// folder that looks like an operator's import
			final CatalogFolderId folderId = CatalogFolderAllocator.allocate(
				storageDirectory, "products", ascendingGenerations()
			);

			assertTrue(
				Files.exists(
					storageDirectory.resolve(folderId.id()).resolve(CatalogPersistenceService.PROVISIONAL_FLAG)
				)
			);
		}
	}

	@Nested
	@DisplayName("Skipping names that cannot be used")
	class BurnAndRetry {

		@Test
		@DisplayName("Burns a generation whose folder already exists and takes the next")
		void shouldBurnGenerationAndRetryWhenNameIsTaken(@TempDir Path storageDirectory) {
			occupy(storageDirectory, "products_1", "products.boot");

			final CatalogFolderId folderId = CatalogFolderAllocator.allocate(
				storageDirectory, "products", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("products_2"), folderId);
		}

		@Test
		@DisplayName("Leaves the folder blocking a candidate name completely untouched")
		void shouldNotDisturbTheFolderOccupyingACandidateName(@TempDir Path storageDirectory) {
			occupy(storageDirectory, "products_1", "products.boot");

			CatalogFolderAllocator.allocate(storageDirectory, "products", ascendingGenerations());

			// allocation must never clobber whatever is sitting on a name it wanted - that folder may be a
			// catalog somebody still needs
			assertTrue(Files.exists(storageDirectory.resolve("products_1").resolve("products.boot")));
		}

		@Test
		@DisplayName("Walks past a run of occupied names")
		void shouldSkipSeveralOccupiedNames(@TempDir Path storageDirectory) {
			occupy(storageDirectory, "products_1");
			occupy(storageDirectory, "products_2");
			occupy(storageDirectory, "products_3");

			final CatalogFolderId folderId = CatalogFolderAllocator.allocate(
				storageDirectory, "products", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("products_4"), folderId);
		}
	}

	@Nested
	@DisplayName("Running out of options")
	class Exhaustion {

		@Test
		@DisplayName("Reports occupied candidates when the storage directory itself is fine")
		void shouldFailWithCandidateDiagnosticWhenEveryNameIsOccupied(@TempDir Path storageDirectory) {
			occupy(storageDirectory, "products_1", "products.boot");
			// a counter stuck on a single number models every candidate being unusable without needing to
			// create sixteen folders
			final IntSupplier stuckOnOne = () -> 1;

			final UnexpectedIOException ex = assertThrows(
				UnexpectedIOException.class,
				() -> CatalogFolderAllocator.allocate(storageDirectory, "products", stuckOnOne)
			);

			assertTrue(
				ex.getPrivateMessage().contains(String.valueOf(CatalogFolderAllocator.MAX_ALLOCATION_ATTEMPTS)),
				"The failure must say how many candidates were tried, got: " + ex.getPrivateMessage()
			);
			assertTrue(
				ex.getPrivateMessage().contains("writable"),
				"The failure must rule out a storage misconfiguration, got: " + ex.getPrivateMessage()
			);
			// and the folder that blocked every attempt is still intact
			assertTrue(Files.exists(storageDirectory.resolve("products_1").resolve("products.boot")));
		}

		@Test
		@DisplayName("Reports the storage directory when it is the thing that is wrong")
		void shouldFailWithStorageDirectoryDiagnosticWhenRootIsMissing(@TempDir Path storageDirectory) {
			final Path missingRoot = storageDirectory.resolve("does-not-exist");

			final UnexpectedIOException ex = assertThrows(
				UnexpectedIOException.class,
				() -> CatalogFolderAllocator.allocate(missingRoot, "products", ascendingGenerations())
			);

			// the two exhaustion modes need different responses from whoever reads the log, so they must not
			// share a message
			assertTrue(
				ex.getPrivateMessage().contains("not a writable directory"),
				"The failure must name the storage directory as the cause, got: " + ex.getPrivateMessage()
			);
		}
	}

	@Nested
	@DisplayName("Clearing the provisional marker")
	class MarkerRemoval {

		@Test
		@DisplayName("Removes the marker so the folder stops looking abandoned")
		void shouldRemoveProvisionalMarker(@TempDir Path storageDirectory) {
			final CatalogFolderId folderId = CatalogFolderAllocator.allocate(
				storageDirectory, "products", ascendingGenerations()
			);
			final Path folder = storageDirectory.resolve(folderId.id());

			CatalogFolderAllocator.clearProvisionalMarker(folder);

			assertTrue(Files.notExists(folder.resolve(CatalogPersistenceService.PROVISIONAL_FLAG)));
			assertTrue(Files.isDirectory(folder), "Clearing the marker must not remove the folder!");
		}

		@Test
		@DisplayName("Tolerates a folder that carries no marker")
		void shouldTolerateMissingMarker(@TempDir Path storageDirectory) {
			// boot-time recovery may clear a marker that a previous run already removed
			occupy(storageDirectory, "products_1");

			CatalogFolderAllocator.clearProvisionalMarker(storageDirectory.resolve("products_1"));

			assertTrue(Files.isDirectory(storageDirectory.resolve("products_1")));
		}
	}

	@Nested
	@DisplayName("Adopting a folder that arrived from outside")
	class Adoption {

		@Test
		@DisplayName("Renames a bare folder into the generation shape, contents and all")
		void shouldRenameBareFolderIntoGenerationShape(@TempDir Path storageDirectory) throws IOException {
			occupy(storageDirectory, "products", "products.boot");

			final CatalogFolderId adopted = CatalogFolderAllocator.adopt(
				storageDirectory, new CatalogFolderId("products"), "products", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("products_1"), adopted);
			assertTrue(Files.notExists(storageDirectory.resolve("products")));
			// the point of the move is that the data comes with it - a rename that emptied the folder would
			// pass a name-only assertion while destroying the catalog it was adopting
			assertTrue(Files.exists(storageDirectory.resolve("products_1").resolve("products.boot")));
		}

		@Test
		@DisplayName("Never marks an adopted folder provisional")
		void shouldNotMarkAdoptedFolderProvisional(@TempDir Path storageDirectory) {
			// the marker means "incomplete, safe to delete" - writing it onto a folder full of somebody's data
			// would arm boot-time cleanup against a catalog that was never mid-write
			occupy(storageDirectory, "products", "products.boot");

			final CatalogFolderId adopted = CatalogFolderAllocator.adopt(
				storageDirectory, new CatalogFolderId("products"), "products", ascendingGenerations()
			);

			assertTrue(
				Files.notExists(
					storageDirectory.resolve(adopted.id()).resolve(CatalogPersistenceService.PROVISIONAL_FLAG)
				)
			);
		}

		@Test
		@DisplayName("Burns a generation whose name is taken and takes the next")
		void shouldBurnGenerationWhenTargetNameIsTaken(@TempDir Path storageDirectory) {
			occupy(storageDirectory, "products", "products.boot");
			occupy(storageDirectory, "products_1", "products.boot");

			final CatalogFolderId adopted = CatalogFolderAllocator.adopt(
				storageDirectory, new CatalogFolderId("products"), "products", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("products_2"), adopted);
		}

		@Test
		@DisplayName("Leaves the folder occupying the target name completely untouched")
		void shouldNotClobberTheFolderOccupyingTheTargetName(@TempDir Path storageDirectory) {
			// `Files.move` without REPLACE_EXISTING is what makes this true, and it is the whole reason the
			// flag is absent: adopting one catalog must never overwrite another
			occupy(storageDirectory, "products", "new.boot");
			occupy(storageDirectory, "products_1", "incumbent.boot");

			CatalogFolderAllocator.adopt(
				storageDirectory, new CatalogFolderId("products"), "products", ascendingGenerations()
			);

			assertTrue(Files.exists(storageDirectory.resolve("products_1").resolve("incumbent.boot")));
		}

		@Test
		@DisplayName("Hands back the original token when every candidate name is taken")
		void shouldFallBackToTheOriginalTokenWhenRenameCannotSucceed(@TempDir Path storageDirectory) {
			// adoption must never fail: a folder that could not be renamed is bound under its bare name and
			// works exactly as well, where refusing would take a readable catalog out of service over cosmetics
			occupy(storageDirectory, "products", "products.boot");
			for (int generation = 1; generation <= CatalogFolderAllocator.MAX_ALLOCATION_ATTEMPTS; generation++) {
				occupy(storageDirectory, "products_" + generation);
			}

			final CatalogFolderId adopted = CatalogFolderAllocator.adopt(
				storageDirectory, new CatalogFolderId("products"), "products", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("products"), adopted);
			assertTrue(Files.exists(storageDirectory.resolve("products").resolve("products.boot")));
		}

		@Test
		@DisplayName("Renames onto the catalog name, not the folder name it came from")
		void shouldRenameOntoTheCatalogNameRatherThanTheFolderName(@TempDir Path storageDirectory) {
			// the two are equal wherever adoption is reached today, because the name is read from the
			// directory. They stop being equal the moment it is read from the catalog header instead, and the
			// rename has to follow the catalog rather than the directory it happened to be sitting in.
			occupy(storageDirectory, "products", "products.boot");

			final CatalogFolderId adopted = CatalogFolderAllocator.adopt(
				storageDirectory, new CatalogFolderId("products"), "orders", ascendingGenerations()
			);

			assertEquals(new CatalogFolderId("orders_1"), adopted);
		}
	}

	@Nested
	@DisplayName("Labelling a folder with its catalog name")
	class NameMarker {

		@Test
		@DisplayName("Writes the catalog name into the folder")
		void shouldWriteCatalogNameMarker(@TempDir Path storageDirectory) throws IOException {
			occupy(storageDirectory, "products_1");
			final Path folder = storageDirectory.resolve("products_1");

			CatalogFolderAllocator.writeCatalogNameMarker(folder, "orders");

			assertEquals(
				"orders",
				Files.readString(folder.resolve(CatalogPersistenceService.CATALOG_NAME_FLAG))
			);
		}

		@Test
		@DisplayName("Overwrites a stale label rather than appending to it")
		void shouldOverwriteStaleCatalogNameMarker(@TempDir Path storageDirectory) throws IOException {
			// a folder outlives renames and replaces, so the label is rewritten far more often than written
			occupy(storageDirectory, "products_1");
			final Path folder = storageDirectory.resolve("products_1");
			CatalogFolderAllocator.writeCatalogNameMarker(folder, "products");

			CatalogFolderAllocator.writeCatalogNameMarker(folder, "orders");

			assertEquals(
				"orders",
				Files.readString(folder.resolve(CatalogPersistenceService.CATALOG_NAME_FLAG))
			);
		}

		@Test
		@DisplayName("Swallows a failure rather than failing the operation that asked for it")
		void shouldNotThrowWhenMarkerCannotBeWritten(@TempDir Path storageDirectory) {
			// nothing in the engine reads this file, so a catalog operation must never die because it could
			// not be written - the folder simply stays unlabelled
			CatalogFolderAllocator.writeCatalogNameMarker(
				storageDirectory.resolve("folder-that-does-not-exist"), "products"
			);
		}
	}
}
