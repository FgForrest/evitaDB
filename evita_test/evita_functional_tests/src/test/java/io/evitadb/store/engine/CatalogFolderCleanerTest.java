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

import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the only code in the folder-decoupling work that destroys anything.
 *
 * Every test here is really the same assertion from a different angle: nothing is removed without positive
 * evidence that evitaDB itself created it. The parametrised row is the load-bearing one — it pins the drain to
 * {@link CatalogFolderState#isDeletable()} rather than letting it re-derive the policy, because two copies of
 * that decision can drift and the drift that matters deletes something the classifier said to keep.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(MANAGEMENT)
@DisplayName("Boot-time catalog folder cleanup")
class CatalogFolderCleanerTest {

	/**
	 * Creates a directory under the storage root and populates it with the given (empty) files.
	 *
	 * @param storageDirectory the storage root
	 * @param folderName       name of the directory to create
	 * @param fileNames        names of the files to create inside it
	 * @return the created directory
	 */
	@Nonnull
	private static Path folder(
		@Nonnull Path storageDirectory,
		@Nonnull String folderName,
		@Nonnull String... fileNames
	) {
		try {
			final Path folder = Files.createDirectory(storageDirectory.resolve(folderName));
			for (final String fileName : fileNames) {
				Files.createFile(folder.resolve(fileName));
			}
			return folder;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Nested
	@DisplayName("Removing folders an operation abandoned")
	class Draining {

		@Test
		@DisplayName("Removes a provisional folder together with everything in it")
		void shouldRemoveProvisionalFolderAndItsContents(@TempDir Path storageDirectory) {
			final Path abandoned = folder(
				storageDirectory, "products_3",
				CatalogPersistenceService.PROVISIONAL_FLAG, "products.boot", "products_0.catalog"
			);

			final List<String> removed = CatalogFolderCleaner.drain(
				storageDirectory,
				List.of(new CatalogFolderClassification(
					"products_3", CatalogFolderState.PROVISIONAL, null
				))
			);

			assertEquals(List.of("products_3"), removed);
			assertTrue(Files.notExists(abandoned));
		}

		@Test
		@DisplayName("Clears nested directories left behind inside an abandoned folder")
		void shouldRemoveNestedDirectories(@TempDir Path storageDirectory) throws IOException {
			final Path abandoned = folder(storageDirectory, "products_3", CatalogPersistenceService.PROVISIONAL_FLAG);
			Files.createFile(Files.createDirectory(abandoned.resolve("nested")).resolve("deep.dat"));

			CatalogFolderCleaner.drain(
				storageDirectory,
				List.of(new CatalogFolderClassification("products_3", CatalogFolderState.PROVISIONAL, null))
			);

			assertTrue(Files.notExists(abandoned));
		}

		@Test
		@DisplayName("Reports nothing when there is nothing to remove")
		void shouldReportNothingRemovedForAnEmptyClassification(@TempDir Path storageDirectory) {
			assertTrue(CatalogFolderCleaner.drain(storageDirectory, List.of()).isEmpty());
		}

		@Test
		@DisplayName("Reports nothing and keeps the data when the folder cannot be deleted")
		void shouldReportNothingWhenTheDeleteFails(@TempDir Path storageDirectory) throws IOException {
			// The report is what discharges the tombstone: a folder named here is one the engine may stop
			// recording that it owes a deletion for. Reporting a folder that is still on disk would strike the
			// tombstone while the data stays - and a folder is never classified again once nothing references
			// it, so nothing would ever refill the entry. That is a permanent leak with no record that the data
			// was meant to go, and the one-line change that causes it is moving `removed.add` above the `try`.
			final Path retired = folder(storageDirectory, "products_2", "products.boot");
			assumeTrue(
				Files.getFileStore(retired).supportsFileAttributeView(PosixFileAttributeView.class),
				"POSIX permissions are required to make a delete fail"
			);

			// readable and traversable, but not writable - so the walk still finds the file and the unlink of
			// it is what fails, which is the shape a real refusal takes
			Files.setPosixFilePermissions(retired, PosixFilePermissions.fromString("r-x------"));
			try {
				assumeFalse(Files.isWritable(retired), "running as root - permissions do not restrict deletes");

				final List<String> removed = CatalogFolderCleaner.drain(
					storageDirectory,
					List.of(new CatalogFolderClassification("products_2", CatalogFolderState.RETIRED, "products"))
				);

				assertTrue(removed.isEmpty(), "A folder that survived the delete must not be reported as removed.");
				assertTrue(
					Files.exists(retired.resolve("products.boot")),
					"The catalog data must still be on disk after a refused delete."
				);
			} finally {
				// restore access so the temporary directory can be cleaned up
				Files.setPosixFilePermissions(retired, PosixFilePermissions.fromString("rwx------"));
			}
		}
	}

	@Nested
	@DisplayName("Refusing to remove anything else")
	class Refusing {

		@ParameterizedTest
		@EnumSource(CatalogFolderState.class)
		@DisplayName("Never removes a folder the classifier did not mark deletable")
		void shouldNeverRemoveANonDeletableFolder(
			@Nonnull CatalogFolderState state,
			@TempDir Path storageDirectory
		) {
			assumeTrue(!state.isDeletable(), "deletable states are covered by the draining tests");
			final Path folder = folder(storageDirectory, "subject_1", "products.boot", "payload.dat");

			final List<String> removed = CatalogFolderCleaner.drain(
				storageDirectory,
				List.of(new CatalogFolderClassification("subject_1", state, null))
			);

			assertTrue(removed.isEmpty(), "State " + state + " must never be drained!");
			assertTrue(Files.exists(folder.resolve("payload.dat")), "State " + state + " lost its contents!");
		}

		@Test
		@DisplayName("Leaves a bound folder alone even when a stale provisional marker survives in it")
		void shouldLeaveBoundFolderCarryingStaleMarkerAlone(@TempDir Path storageDirectory) {
			// the classifier resolves this to REFERENCED, and the drain is the place that would actually do the
			// damage - so the rule is asserted here too, not only where it is decided
			final Path bound = folder(
				storageDirectory, "products_1", CatalogPersistenceService.PROVISIONAL_FLAG, "products.boot"
			);

			final List<String> removed = CatalogFolderCleaner.drain(
				storageDirectory,
				List.of(new CatalogFolderClassification("products_1", CatalogFolderState.REFERENCED, "products"))
			);

			assertTrue(removed.isEmpty());
			assertTrue(Files.exists(bound.resolve("products.boot")));
		}

		@Test
		@DisplayName("Removes a tombstoned folder and reports it, so its tombstone can be discharged")
		void shouldRemoveRetiredFolders(@TempDir Path storageDirectory) {
			// a tombstone is positive evidence that evitaDB unbound this folder itself, which is what authorises
			// destroying it - and reporting the removal is what stops the tombstone outliving the folder
			final Path retired = folder(storageDirectory, "products_2", "products.boot");

			final List<String> removed = CatalogFolderCleaner.drain(
				storageDirectory,
				List.of(new CatalogFolderClassification("products_2", CatalogFolderState.RETIRED, "products"))
			);

			assertEquals(List.of("products_2"), removed);
			assertTrue(Files.notExists(retired));
		}
	}

	@Nested
	@DisplayName("Staying inside the storage directory")
	class Containment {

		@Test
		@DisplayName("Deletes a symbolic link without following it out of the storage directory")
		void shouldNotFollowSymbolicLinkOutOfStorageDirectory(@TempDir Path root) throws IOException {
			// the one bug in this subsystem that destroys something nobody chose: a link inside a folder we are
			// entitled to remove reaches data we are not
			final Path outside = Files.createDirectory(root.resolve("outside"));
			final Path treasure = Files.createFile(outside.resolve("treasure.dat"));
			final Path storageDirectory = Files.createDirectory(root.resolve("storage"));
			final Path abandoned = folder(storageDirectory, "products_3", CatalogPersistenceService.PROVISIONAL_FLAG);
			try {
				Files.createSymbolicLink(abandoned.resolve("escape"), outside);
			} catch (UnsupportedOperationException | FileSystemException ex) {
				assumeTrue(false, "symbolic links are not available on this platform: " + ex.getMessage());
			}

			final List<String> removed = CatalogFolderCleaner.drain(
				storageDirectory,
				List.of(new CatalogFolderClassification("products_3", CatalogFolderState.PROVISIONAL, null))
			);

			assertEquals(List.of("products_3"), removed);
			assertTrue(Files.notExists(abandoned), "The abandoned folder itself must be gone!");
			assertTrue(Files.exists(treasure), "Data outside the storage directory must never be touched!");
			assertTrue(Files.exists(outside), "The link target directory must survive!");
		}
	}
}
