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
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.RetiredFolder;
import io.evitadb.store.model.reference.LogFileRecordReference;
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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.List;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the six-way classification every directory under the storage root is put through at boot.
 *
 * These tests exist *before* the classifier they exercise, deliberately: this is the one place in the folder
 * decoupling work where a wrong answer destroys user data rather than merely failing an operation. The rows
 * that earn that ordering are the **negative** ones — `unclaimed` and `junk` assert that nothing is removed.
 * A suite that only proved "referenced loads, provisional deletes, retired deletes" would pass while the
 * data-loss bug shipped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(MANAGEMENT)
@DisplayName("Boot-time catalog folder classification")
class CatalogFolderClassifierTest {

	/**
	 * Builds an engine state carrying nothing but the folder bindings and tombstones under test.
	 *
	 * @param bindings name-to-folder bindings, ascending by catalog name
	 * @param retired  tombstones, ascending by folder token
	 * @return engine state usable as the classifier's second argument
	 */
	@Nonnull
	private static EngineState<LogFileRecordReference> stateWith(
		@Nonnull CatalogFolderBinding[] bindings,
		@Nonnull RetiredFolder[] retired
	) {
		return new EngineState<>(
			1, 1L, OffsetDateTime.parse("2026-01-01T00:00:00Z"), null,
			new String[0], new String[0], new String[0], new String[0],
			bindings, retired, EngineState.NO_GENERATION_PEAKS
		);
	}

	/**
	 * Creates a directory under the storage root and populates it with the given (empty) files.
	 *
	 * @param storageDirectory the storage root
	 * @param folderName       name of the directory to create
	 * @param fileNames        names of the files to create inside it
	 */
	private static void folder(
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

	/**
	 * Returns the single classification produced for the given folder, failing if it is absent.
	 *
	 * @param classifications everything the classifier returned
	 * @param folderName      folder whose verdict is wanted
	 * @return the matching classification
	 */
	@Nonnull
	private static CatalogFolderClassification verdictFor(
		@Nonnull List<CatalogFolderClassification> classifications,
		@Nonnull String folderName
	) {
		return classifications.stream()
			.filter(it -> it.folderName().equals(folderName))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No classification produced for folder `" + folderName + "`!"));
	}

	@Nested
	@DisplayName("Referenced folders")
	class Referenced {

		@Test
		@DisplayName("Classifies a folder the engine state binds a catalog to")
		void shouldClassifyBoundFolderAsReferenced(@TempDir Path storageDirectory) {
			folder(storageDirectory, "products_1", "products.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory,
				stateWith(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("products", new CatalogFolderId("products_1"))
					},
					EngineState.NO_RETIRED_FOLDERS
				)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "products_1");
			assertEquals(CatalogFolderState.REFERENCED, verdict.state());
			assertEquals("products", verdict.catalogName());
			assertFalse(verdict.state().isDeletable());
		}

		@Test
		@DisplayName("Recognises a bound folder that is still suffix-free after a failed adoption rename")
		void shouldClassifySuffixFreeBoundFolderAsReferenced(@TempDir Path storageDirectory) {
			// adoption allocates a generation and renames; when the rename fails the bare name stays bound and
			// the migration retries next boot, so a *referenced* folder may transiently carry no suffix
			folder(storageDirectory, "products", "products.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory,
				stateWith(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("products", new CatalogFolderId("products"))
					},
					EngineState.NO_RETIRED_FOLDERS
				)
			);

			// it must NOT come out as foreign - re-adopting an already-adopted folder would register it twice
			assertEquals(CatalogFolderState.REFERENCED, verdictFor(result, "products").state());
		}
	}

	@Nested
	@DisplayName("Folders that may be removed")
	class Removable {

		@Test
		@DisplayName("Classifies an unreferenced folder carrying the provisional marker")
		void shouldClassifyMarkedFolderAsProvisional(@TempDir Path storageDirectory) {
			folder(storageDirectory, "products_2", CatalogPersistenceService.PROVISIONAL_FLAG);

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "products_2");
			assertEquals(CatalogFolderState.PROVISIONAL, verdict.state());
			assertTrue(verdict.state().isDeletable());
		}

		@Test
		@DisplayName("Classifies a folder the engine state carries a tombstone for")
		void shouldClassifyTombstonedFolderAsRetired(@TempDir Path storageDirectory) {
			folder(storageDirectory, "products_3", "products.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory,
				stateWith(
					EngineState.NO_FOLDER_BINDINGS,
					new RetiredFolder[]{
						new RetiredFolder("products", new CatalogFolderId("products_3"))
					}
				)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "products_3");
			assertEquals(CatalogFolderState.RETIRED, verdict.state());
			assertEquals("products", verdict.catalogName());
			assertTrue(verdict.state().isDeletable());
		}
	}

	@Nested
	@DisplayName("Foreign folders offered for adoption")
	class Foreign {

		@Test
		@DisplayName("Classifies an unreferenced suffix-free folder holding a bootstrap file")
		void shouldClassifySuffixFreeFolderWithBootstrapAsForeign(@TempDir Path storageDirectory) {
			folder(storageDirectory, "imported", "imported.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "imported");
			assertEquals(CatalogFolderState.FOREIGN, verdict.state());
			// the catalog name comes from the bootstrap header at adoption time, not from the directory name -
			// trusting the directory name is the hole that lets an import shadow a live catalog
			assertNull(verdict.catalogName());
			assertFalse(verdict.state().isDeletable());
		}

		@Test
		@DisplayName("Treats a name whose trailing underscore carries no digits as suffix-free")
		void shouldTreatNonNumericTrailingSegmentAsSuffixFree(@TempDir Path storageDirectory) {
			// catalog names legally contain underscores, so only `_<digits>` counts as a generation suffix
			folder(storageDirectory, "my_catalog", "my_catalog.boot");
			folder(storageDirectory, "trailing_", "trailing_.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			assertEquals(CatalogFolderState.FOREIGN, verdictFor(result, "my_catalog").state());
			assertEquals(CatalogFolderState.FOREIGN, verdictFor(result, "trailing_").state());
		}
	}

	@Nested
	@DisplayName("Folders that must never be touched")
	class LeftAlone {

		@Test
		@DisplayName("Leaves an unreferenced suffixed folder alone rather than reclaiming it")
		void shouldClassifySuffixedUnreferencedFolderAsUnclaimed(@TempDir Path storageDirectory) {
			// this is the operator who copied `products_7` in from another instance. The suffix-free rule tells
			// them not to, and they did it anyway - deleting it would be unrecoverable data loss
			folder(storageDirectory, "products_7", "products.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "products_7");
			assertEquals(CatalogFolderState.UNCLAIMED, verdict.state());
			assertFalse(verdict.state().isDeletable(), "An unclaimed folder must never be deleted!");
		}

		@Test
		@DisplayName("Leaves a folder holding no bootstrap file alone")
		void shouldClassifyFolderWithoutBootstrapAsJunk(@TempDir Path storageDirectory) {
			folder(storageDirectory, "leftovers", "notes.txt");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "leftovers");
			assertEquals(CatalogFolderState.JUNK, verdict.state());
			assertFalse(verdict.state().isDeletable(), "A junk folder must never be deleted!");
		}

		@Test
		@DisplayName("Leaves a directory it cannot read alone rather than guessing")
		void shouldClassifyUnreadableDirectoryAsUnclaimed(@TempDir Path storageDirectory) throws IOException {
			final Path folder = Files.createDirectory(storageDirectory.resolve("locked_1"));
			Files.createFile(folder.resolve("products.boot"));
			assumeTrue(
				Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class),
				"POSIX permissions are required to make a directory unlistable"
			);

			Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("---------"));
			try {
				assumeFalse(Files.isReadable(folder), "running as root - permissions do not restrict reads");

				final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
					storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
				);

				// "cannot determine" must resolve to the non-destructive row - never to a guess, and never to a
				// failure that would stop the whole engine booting over one bad folder
				final CatalogFolderClassification verdict = verdictFor(result, "locked_1");
				assertEquals(CatalogFolderState.UNCLAIMED, verdict.state());
				assertFalse(verdict.state().isDeletable(), "An unreadable folder must never be deleted!");
			} finally {
				// restore access so the temporary directory can be cleaned up
				Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
			}
		}

		@Test
		@DisplayName("Prefers junk over unclaimed when a suffixed folder holds no bootstrap file")
		void shouldClassifySuffixedFolderWithoutBootstrapAsJunk(@TempDir Path storageDirectory) {
			// both rows resolve to warn-and-leave, so the choice is purely about the advice in the warning:
			// telling someone to rename this one for adoption would be wrong, since adoption needs a `*.boot`
			folder(storageDirectory, "products_9");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			assertEquals(CatalogFolderState.JUNK, verdictFor(result, "products_9").state());
		}
	}

	@Nested
	@DisplayName("Overlapping evidence")
	class Overlaps {

		@Test
		@DisplayName("Loads a bound folder whose provisional marker outlived its creation")
		void shouldPreferReferencedOverProvisional(@TempDir Path storageDirectory) {
			// the marker is removed before the binding commits, so this state is unreachable by construction -
			// but if it is ever observed the binding wins, because deleting a bound folder is data loss
			folder(storageDirectory, "products_1", "products.boot", CatalogPersistenceService.PROVISIONAL_FLAG);

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory,
				stateWith(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("products", new CatalogFolderId("products_1"))
					},
					EngineState.NO_RETIRED_FOLDERS
				)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "products_1");
			assertEquals(CatalogFolderState.REFERENCED, verdict.state());
			assertFalse(verdict.state().isDeletable());
		}

		@Test
		@DisplayName("Loads a bound folder that a stale tombstone still names")
		void shouldPreferReferencedOverRetired(@TempDir Path storageDirectory) {
			folder(storageDirectory, "products_1", "products.boot");

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory,
				stateWith(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding("products", new CatalogFolderId("products_1"))
					},
					new RetiredFolder[]{
						new RetiredFolder("products", new CatalogFolderId("products_1"))
					}
				)
			);

			final CatalogFolderClassification verdict = verdictFor(result, "products_1");
			assertEquals(CatalogFolderState.REFERENCED, verdict.state());
			assertFalse(verdict.state().isDeletable(), "A bound folder must survive a stale tombstone!");
		}

		@Test
		@DisplayName("Never marks a bound folder deletable, whatever else is present in it")
		void shouldNeverDeleteBoundFolderUnderAnyMarkerCombination(@TempDir Path storageDirectory) {
			// the single invariant the whole table exists to protect, asserted independently of the rows
			final String[][] contents = {
				{},
				{"products.boot"},
				{CatalogPersistenceService.PROVISIONAL_FLAG},
				{CatalogPersistenceService.RESTORE_FLAG},
				{"products.boot", CatalogPersistenceService.PROVISIONAL_FLAG},
				{"products.boot", CatalogPersistenceService.PROVISIONAL_FLAG, CatalogPersistenceService.RESTORE_FLAG}
			};

			final CatalogFolderBinding[] bindings = new CatalogFolderBinding[contents.length];
			for (int i = 0; i < contents.length; i++) {
				final String catalogName = "catalog" + i;
				folder(storageDirectory, catalogName + "_1", contents[i]);
				bindings[i] = new CatalogFolderBinding(catalogName, new CatalogFolderId(catalogName + "_1"));
			}

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory,
				stateWith(
					bindings,
					// every one of them is simultaneously tombstoned, which must still not win
					new RetiredFolder[]{
						new RetiredFolder("catalog0", new CatalogFolderId("catalog0_1"))
					}
				)
			);

			for (int i = 0; i < contents.length; i++) {
				final CatalogFolderClassification verdict = verdictFor(result, "catalog" + i + "_1");
				assertEquals(
					CatalogFolderState.REFERENCED, verdict.state(),
					"Bound folder `catalog" + i + "_1` was not classified as referenced!"
				);
				assertFalse(
					verdict.state().isDeletable(),
					"Bound folder `catalog" + i + "_1` was marked deletable!"
				);
			}
		}
	}

	@Nested
	@DisplayName("Result shape")
	class ResultShape {

		@Test
		@DisplayName("Returns one verdict per directory, ordered by folder name")
		void shouldReturnOneDeterministicallyOrderedVerdictPerDirectory(@TempDir Path storageDirectory) {
			folder(storageDirectory, "zulu_1", "zulu.boot");
			folder(storageDirectory, "alpha", "alpha.boot");
			folder(storageDirectory, "mike_2");
			// a loose file directly under the storage root is not a catalog folder and must be ignored
			try {
				Files.createFile(storageDirectory.resolve("stray.txt"));
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}

			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			assertEquals(3, result.size());
			assertEquals("alpha", result.get(0).folderName());
			assertEquals("mike_2", result.get(1).folderName());
			assertEquals("zulu_1", result.get(2).folderName());
		}

		@Test
		@DisplayName("Returns nothing for an empty storage directory")
		void shouldReturnNothingForEmptyStorageDirectory(@TempDir Path storageDirectory) {
			final List<CatalogFolderClassification> result = CatalogFolderClassifier.classify(
				storageDirectory, stateWith(EngineState.NO_FOLDER_BINDINGS, EngineState.NO_RETIRED_FOLDERS)
			);

			assertTrue(result.isEmpty());
		}
	}
}
