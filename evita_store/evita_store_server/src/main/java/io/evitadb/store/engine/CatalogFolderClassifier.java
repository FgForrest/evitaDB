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
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.RetiredFolder;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.evitadb.spi.store.catalog.persistence.PersistenceService.BOOT_FILE_SUFFIX;

/**
 * Decides what every directory under the storage root actually is, so boot-time cleanup knows which of them it
 * is allowed to destroy.
 *
 * The classification is a pure function of the directory shapes on disk and the persisted engine state; it
 * performs no side effects, which is what makes it directly testable rather than reachable only through a
 * persistence-service constructor. Deleting the wrong folder here is unrecoverable, so the tests covering it are
 * written first and the negative rows — {@link CatalogFolderState#UNCLAIMED}, {@link CatalogFolderState#JUNK} —
 * are the ones that matter.
 *
 * Evaluation order is significant and is **not** quite the order the states are declared in:
 *
 * 1. a bound folder is `REFERENCED`, whatever else it contains — see below;
 * 2. then the two states that carry positive evidence of our ownership, `PROVISIONAL` and `RETIRED`;
 * 3. then, for whatever is left, a folder holding no bootstrap file is `JUNK` — checked *before* the
 *    suffix, because the alternative advice ("rename it for adoption") is wrong for a folder adoption
 *    could never read;
 * 4. finally the suffix splits the remainder: suffix-free is `FOREIGN` and adoptable, suffixed is
 *    `UNCLAIMED` and left alone.
 *
 * **Referenced wins over every other row.** The binding in the engine state is the sole authority on where a
 * catalog lives, so a stale marker or a stale tombstone found on a bound folder is litter to clear, never grounds
 * for deletion. Ordering it first is what makes the remaining rows safe to act on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class CatalogFolderClassifier {

	private CatalogFolderClassifier() {
		// utility class, never instantiated
	}

	/**
	 * Classifies every directory found directly under the given storage root.
	 *
	 * Loose files under the root are ignored — only directories can hold a catalog. The result is ordered by
	 * folder name so the warnings, and the WAL trail derived from them, are identical across reboots over the
	 * same on-disk shape.
	 *
	 * @param storageDirectory root directory the engine stores catalogs under
	 * @param engineState      persisted state naming the folders that are bound or tombstoned
	 * @return one verdict per directory, ascending by folder name; never null
	 */
	@Nonnull
	public static List<CatalogFolderClassification> classify(
		@Nonnull Path storageDirectory,
		@Nonnull EngineState<?> engineState
	) {
		final CatalogFolderBinding[] bindings = engineState.catalogFolders();
		final Map<String, String> catalogNameByBoundFolder = CollectionUtils.createHashMap(bindings.length);
		for (final CatalogFolderBinding binding : bindings) {
			catalogNameByBoundFolder.put(binding.folderId().id(), binding.catalogName());
		}

		final RetiredFolder[] retiredFolders = engineState.retiredFolders();
		final Map<String, String> catalogNameByRetiredFolder = CollectionUtils.createHashMap(retiredFolders.length);
		for (final RetiredFolder retiredFolder : retiredFolders) {
			catalogNameByRetiredFolder.put(retiredFolder.folderId().id(), retiredFolder.catalogName());
		}

		final Path[] directories = FileUtils.listDirectories(storageDirectory);
		final List<CatalogFolderClassification> result = new ArrayList<>(directories.length);
		for (final Path directory : directories) {
			result.add(
				classifyOne(directory, catalogNameByBoundFolder, catalogNameByRetiredFolder)
			);
		}
		result.sort(Comparator.comparing(CatalogFolderClassification::folderName));
		return result;
	}

	/**
	 * Applies the ordered rules described on the class to a single directory.
	 *
	 * @param directory                 directory to classify
	 * @param catalogNameByBoundFolder  folder token to catalog name, for folders a catalog is bound to
	 * @param catalogNameByRetiredFolder folder token to catalog name, for folders awaiting deletion
	 * @return the verdict for this directory
	 */
	@Nonnull
	private static CatalogFolderClassification classifyOne(
		@Nonnull Path directory,
		@Nonnull Map<String, String> catalogNameByBoundFolder,
		@Nonnull Map<String, String> catalogNameByRetiredFolder
	) {
		final String folderName = directory.getFileName().toString();

		// 1. a bound folder is loaded whatever else it holds - deleting one is data loss by definition
		final String boundCatalogName = catalogNameByBoundFolder.get(folderName);
		if (boundCatalogName != null) {
			return new CatalogFolderClassification(folderName, CatalogFolderState.REFERENCED, boundCatalogName);
		}

		final FolderContents contents;
		try {
			contents = readContents(directory);
		} catch (IOException ex) {
			// the directory exists but cannot be read - we genuinely do not know what it is, and "do not know"
			// resolves to the non-destructive row rather than to a guess. Boot must not fail over one bad folder,
			// but the cause has to reach the log or the warning is unactionable.
			log.warn(
				"Storage folder `{}` cannot be read and will be left untouched - check its permissions.",
				folderName, ex
			);
			return new CatalogFolderClassification(folderName, CatalogFolderState.UNCLAIMED, null);
		}

		// 2. positive evidence that this folder is ours, and that nothing reachable lives in it
		if (contents.provisional()) {
			return new CatalogFolderClassification(folderName, CatalogFolderState.PROVISIONAL, null);
		}
		final String retiredCatalogName = catalogNameByRetiredFolder.get(folderName);
		if (retiredCatalogName != null) {
			return new CatalogFolderClassification(folderName, CatalogFolderState.RETIRED, retiredCatalogName);
		}

		// 3. without a bootstrap file nothing can be read from the folder, so adoption is not on the table
		if (!contents.bootstrap()) {
			return new CatalogFolderClassification(folderName, CatalogFolderState.JUNK, null);
		}

		// 4. suffix-free is the documented shape for hand-placing a catalog; a suffix means it looks like ours
		//    while nothing claims it, which is the case that must be reported rather than reclaimed
		return new CatalogFolderClassification(
			folderName,
			hasGenerationSuffix(folderName) ? CatalogFolderState.UNCLAIMED : CatalogFolderState.FOREIGN,
			null
		);
	}

	/**
	 * Reads the two facts about a folder's contents the classification needs, in a single directory scan.
	 *
	 * Deliberately avoids `Files.exists` per marker: that method answers `false` both when a path is absent and
	 * when its existence cannot be determined, so it would silently misreport a folder whose permissions changed.
	 * One listing gives an honest answer or an `IOException`, and never a confident wrong one.
	 *
	 * @param directory directory to scan
	 * @return what the directory holds
	 * @throws IOException when the directory cannot be listed
	 */
	@Nonnull
	private static FolderContents readContents(@Nonnull Path directory) throws IOException {
		boolean provisional = false;
		boolean bootstrap = false;
		try (final DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (final Path entry : entries) {
				final String entryName = entry.getFileName().toString();
				if (CatalogPersistenceService.PROVISIONAL_FLAG.equals(entryName)) {
					provisional = true;
				} else if (entryName.endsWith(BOOT_FILE_SUFFIX)) {
					bootstrap = true;
				}
			}
		}
		return new FolderContents(provisional, bootstrap);
	}

	/**
	 * Tells whether a folder name ends in the `_<generation>` suffix the engine appends when it allocates one.
	 *
	 * Only a trailing underscore followed by at least one digit counts. Catalog names legally contain
	 * underscores, so `my_catalog` is suffix-free while `my_catalog_3` is not.
	 *
	 * The digit run is checked loosely on purpose — `products_007` and an absurdly long run both pass, though
	 * the allocator only ever writes canonical decimal, so neither can be a folder evitaDB produced. Every false
	 * positive lands on {@link CatalogFolderState#UNCLAIMED}, which is the row that touches nothing; tightening
	 * the check could only move a folder *towards* being adopted, which is the direction that carries risk.
	 *
	 * @param folderName folder name to examine
	 * @return true when the name carries a generation suffix
	 */
	private static boolean hasGenerationSuffix(@Nonnull String folderName) {
		final int lastUnderscore = folderName.lastIndexOf('_');
		if (lastUnderscore < 0 || lastUnderscore == folderName.length() - 1) {
			return false;
		}
		for (int i = lastUnderscore + 1; i < folderName.length(); i++) {
			if (!Character.isDigit(folderName.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The two facts a single directory listing has to yield.
	 *
	 * @param provisional whether the folder carries the provisional marker
	 * @param bootstrap   whether the folder holds a bootstrap file
	 */
	private record FolderContents(
		boolean provisional,
		boolean bootstrap
	) {
	}

}
