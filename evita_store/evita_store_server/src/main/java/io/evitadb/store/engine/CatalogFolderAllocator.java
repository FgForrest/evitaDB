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
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Creates the directory a catalog's data will live in, and hands back the opaque token that names it.
 *
 * The generation is drawn from an engine-scoped counter supplied by the caller — the allocator is deliberately
 * ignorant of where the numbers come from, which is what keeps it testable without an engine and keeps the
 * storage layer free of a dependency on engine internals.
 *
 * **The directory creation is the test.** There is no `Files.exists` pre-check, because that method answers
 * `false` both when a path is absent and when its existence cannot be determined — it reports an
 * `AccessDeniedException` as absence — so it can call a name free that creation then rejects.
 * `Files.createDirectory` collapses the check and the act into one atomic syscall and cannot be raced.
 *
 * **A failed creation burns its number and moves on.** This is what keeps a rename or replace *live*. A folder
 * left behind by an operation that died can be one the filesystem refuses to clear, or reports as cleared while
 * still refusing to recreate the name — Windows marks a directory delete-pending while a handle is open. An
 * allocator that drew one number and gave up would retry that same name after every restart and fail identically
 * forever, leaving the catalog permanently unreplaceable, with each attempt surfacing an ordinary filesystem
 * error so the livelock reads as an environment problem.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class CatalogFolderAllocator {

	/**
	 * How many candidate names are tried before allocation is declared impossible.
	 *
	 * Generous enough that exhausting it means something is systematically wrong rather than unlucky — the
	 * counter only ever moves forward, so distinct attempts never collide with each other.
	 */
	public static final int MAX_ALLOCATION_ATTEMPTS = 16;

	private CatalogFolderAllocator() {
		// utility class, never instantiated
	}

	/**
	 * Allocates a fresh folder for the given catalog and marks it provisional.
	 *
	 * The marker goes in immediately, so the folder is never observable as unexplained litter for longer than a
	 * single filesystem operation. The caller must clear it with
	 * {@link #clearProvisionalMarker(Path)} **before** committing the engine-state binding that points at this
	 * folder — the other order leaves a bound folder wearing an "incomplete" marker, which the boot classifier
	 * would have to resolve against itself.
	 *
	 * @param storageDirectory   root directory catalogs are stored under
	 * @param catalogName        name of the catalog the folder is being allocated for
	 * @param generationSupplier source of generation numbers; must never return the same number twice
	 * @return token naming the freshly created folder
	 * @throws UnexpectedIOException when no candidate name could be created
	 */
	@Nonnull
	public static CatalogFolderId allocate(
		@Nonnull Path storageDirectory,
		@Nonnull String catalogName,
		@Nonnull IntSupplier generationSupplier
	) {
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ALLOCATION_ATTEMPTS; attempt++) {
			final String folderName = catalogName + '_' + generationSupplier.getAsInt();
			final Path folder = storageDirectory.resolve(folderName);
			try {
				Files.createDirectory(folder);
			} catch (IOException ex) {
				// the name is taken, or unusable for a reason we cannot see - burn it and draw the next
				log.debug("Folder `{}` could not be created ({}), trying the next generation.", folderName,
					ex.getMessage());
				lastFailure = ex;
				continue;
			}
			try {
				Files.createFile(folder.resolve(CatalogPersistenceService.PROVISIONAL_FLAG));
				return new CatalogFolderId(folderName);
			} catch (IOException ex) {
				// The directory itself was created, so it is ours and nothing else can be occupying it. Remove it
				// before moving on: an unmarked empty folder is indistinguishable from an operator's leftovers,
				// so boot classification would only ever warn about it and it would linger forever.
				// Not covered by a test - inducing a create-file failure inside a directory we just successfully
				// created needs a seam that exists only for the test, and the branch is three lines long.
				log.debug("Folder `{}` could not be marked provisional ({}), removing it again.", folderName,
					ex.getMessage());
				lastFailure = ex;
				removeEmptyFolderQuietly(folder);
			}
		}
		throw allocationFailed(storageDirectory, catalogName, lastFailure);
	}

	/**
	 * Brings a folder that arrived from outside into the canonical `<catalogName>_<generation>` shape, by renaming
	 * it in place.
	 *
	 * This runs at boot, before any catalog is opened, which is the only moment a folder rename is genuinely safe:
	 * every handle is closed, so the move cannot fail for the reason folder moves usually fail. It serves both a
	 * folder an operator hand-placed and one an older evitaDB left behind in the bare-name layout — the two are
	 * indistinguishable on disk and deliberately take one code path, so each is exercised by the other's tests.
	 *
	 * **Failing to rename is not an error.** The caller binds whichever token comes back, so an unrenamed folder is
	 * simply bound under its bare name and works exactly as well; the classification table tolerates a referenced
	 * suffix-free folder because the referenced row is matched before the foreign row.
	 *
	 * What it costs is that the folder stays outside the generation scheme **permanently**, not until the next
	 * boot: once bound it classifies as referenced, which is matched first, so adoption never sees it again. The
	 * retry the design imagines is the boot-time rename of *referenced* folders whose name no longer matches their
	 * catalog — the same pass that brings a renamed catalog's folder back in line — and that does not exist yet.
	 * A cosmetic debt either way, and far cheaper than refusing to adopt a catalog whose data is perfectly
	 * readable.
	 *
	 * A generation is burned per attempt, exactly as in {@link #allocate(Path, String, IntSupplier)} and for the
	 * same reason: retrying the same name after every restart would wedge the migration forever on a name the
	 * filesystem refuses to free.
	 *
	 * @param storageDirectory   root directory catalogs are stored under
	 * @param folderId           token naming the folder as it currently exists on disk
	 * @param catalogName        name of the catalog whose data the folder holds — the new name's cosmetic half
	 * @param generationSupplier source of generation numbers, one drawn per attempt
	 * @return token naming the folder afterwards: the renamed one on success, the original one otherwise
	 */
	@Nonnull
	public static CatalogFolderId adopt(
		@Nonnull Path storageDirectory,
		@Nonnull CatalogFolderId folderId,
		@Nonnull String catalogName,
		@Nonnull IntSupplier generationSupplier
	) {
		final Path source = storageDirectory.resolve(folderId.id());
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ALLOCATION_ATTEMPTS; attempt++) {
			final String folderName = catalogName + '_' + generationSupplier.getAsInt();
			try {
				// no REPLACE_EXISTING: an occupied target must fail the attempt rather than destroy whatever
				// holds the name, which is the same atomic test-and-set `createDirectory` gives the allocator
				Files.move(source, storageDirectory.resolve(folderName));
				log.info(
					"Adopted storage folder `{}` for catalog `{}` and renamed it to `{}`.",
					folderId.id(), catalogName, folderName
				);
				return new CatalogFolderId(folderName);
			} catch (IOException ex) {
				log.debug("Folder `{}` could not be renamed to `{}` ({}), trying the next generation.",
					folderId.id(), folderName, ex.getMessage());
				lastFailure = ex;
			}
		}
		log.warn(
			"Storage folder `{}` holding catalog `{}` could not be renamed into the `{}_<generation>` shape after " +
				"{} attempts - adopting it under its current name instead. The catalog is fully usable; only the " +
				"folder's name is off-convention, and nothing will rename it later.",
			folderId.id(), catalogName, catalogName, MAX_ALLOCATION_ATTEMPTS, lastFailure
		);
		return folderId;
	}

	/**
	 * Records which catalog a folder belongs to, in a marker file inside the folder itself.
	 *
	 * Written on a best-effort basis and never propagated as a failure: nothing in the engine reads this file to
	 * make a decision — the engine state is the sole authority on where a catalog lives — so a folder without it
	 * is fully functional. It exists for the operator doing disaster recovery against a bare storage directory
	 * with no server to ask, and failing a catalog operation over a file that only humans read would be the wrong
	 * trade entirely.
	 *
	 * @param catalogFolder folder to write the marker into
	 * @param catalogName   name of the catalog the folder holds
	 */
	public static void writeCatalogNameMarker(@Nonnull Path catalogFolder, @Nonnull String catalogName) {
		final Path marker = catalogFolder.resolve(CatalogPersistenceService.CATALOG_NAME_FLAG);
		try {
			Files.writeString(marker, catalogName, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			log.warn(
				"Failed to record catalog name `{}` in `{}` - the folder stays unlabelled, which affects only " +
					"manual inspection of the storage directory.", catalogName, marker.toAbsolutePath(), ex
			);
		}
	}

	/**
	 * Scans the storage directory and reports the highest generation observed per catalog name.
	 *
	 * Parsing lives here because this class owns the `<catalogName>_<generation>` convention that produced the
	 * names — a second parser elsewhere would be free to disagree with the formatter about what a suffix is.
	 *
	 * Folder names carrying no numeric suffix are skipped: they are legacy or hand-placed folders, which by
	 * definition never came from this allocator and so say nothing about which numbers it has handed out.
	 *
	 * @param storageDirectory root directory catalogs are stored under
	 * @return highest generation seen per catalog name; never null, possibly empty
	 */
	@Nonnull
	public static Map<String, Integer> observedPeaks(@Nonnull Path storageDirectory) {
		final Map<String, Integer> peaks = new HashMap<>();
		for (final Path directory : FileUtils.listDirectories(storageDirectory)) {
			final String folderName = directory.getFileName().toString();
			final int lastUnderscore = folderName.lastIndexOf('_');
			if (lastUnderscore <= 0 || lastUnderscore == folderName.length() - 1) {
				// no separator, nothing before it, or nothing after it - not a name this allocator produced
				continue;
			}
			final int generation;
			try {
				generation = Integer.parseInt(folderName.substring(lastUnderscore + 1));
			} catch (NumberFormatException ex) {
				// a catalog legitimately named `my_catalog` lands here - not our shape, and not an error
				continue;
			}
			if (generation > 0) {
				peaks.merge(folderName.substring(0, lastUnderscore), generation, Math::max);
			}
		}
		return peaks;
	}

	/**
	 * Removes the provisional marker from a folder, declaring its contents complete.
	 *
	 * Must run **before** the engine state commits the binding that references the folder. A crash in the window
	 * this opens leaves an unreferenced, marker-free folder, which the boot classifier reports as unclaimed and
	 * leaves alone — litter rather than a half-populated catalog presented as loadable.
	 *
	 * @param catalogFolder folder whose marker is to be removed
	 * @throws UnexpectedIOException when the marker exists but cannot be removed
	 */
	public static void clearProvisionalMarker(@Nonnull Path catalogFolder) {
		try {
			Files.deleteIfExists(catalogFolder.resolve(CatalogPersistenceService.PROVISIONAL_FLAG));
		} catch (IOException ex) {
			throw new UnexpectedIOException(
				"Failed to clear the provisional marker in `" + catalogFolder.toAbsolutePath() + "`: "
					+ ex.getMessage(),
				"Failed to clear the provisional marker of a newly created catalog folder!",
				ex
			);
		}
	}

	/**
	 * Removes a freshly created, still-empty folder whose marker could not be written.
	 *
	 * Failing to remove it is not worth aborting the allocation over — the retry will simply take the next
	 * generation — but it is worth saying out loud, because what stays behind is litter no later boot will
	 * reclaim: an unmarked empty folder carries no evidence that evitaDB owns it.
	 *
	 * @param folder folder to remove
	 */
	private static void removeEmptyFolderQuietly(@Nonnull Path folder) {
		try {
			Files.deleteIfExists(folder);
		} catch (IOException ex) {
			log.warn(
				"Failed to remove folder `{}` left behind by an allocation that could not mark it - it will have " +
					"to be removed by hand.", folder.toAbsolutePath(), ex
			);
		}
	}

	/**
	 * Builds the terminal error, distinguishing the two ways allocation runs out of options.
	 *
	 * They need different responses from whoever reads the log — one is a storage misconfiguration, the other is
	 * litter blocking a name — and are otherwise indistinguishable, since both surface as a string of ordinary
	 * IO failures.
	 *
	 * @param storageDirectory root directory catalogs are stored under
	 * @param catalogName      catalog the allocation was for
	 * @param lastFailure      failure the final attempt produced, if any
	 * @return the exception to throw
	 */
	@Nonnull
	private static UnexpectedIOException allocationFailed(
		@Nonnull Path storageDirectory,
		@Nonnull String catalogName,
		@Nullable IOException lastFailure
	) {
		final String location = storageDirectory.toAbsolutePath().toString();
		if (!Files.isDirectory(storageDirectory) || !Files.isWritable(storageDirectory)) {
			return lastFailure == null ?
				new UnexpectedIOException(
					"Storage directory `" + location + "` is not a writable directory!",
					"Storage directory is not writable - no catalog folder could be allocated!"
				) :
				new UnexpectedIOException(
					"Storage directory `" + location + "` is not a writable directory!",
					"Storage directory is not writable - no catalog folder could be allocated!",
					lastFailure
				);
		}
		final String privateMessage = "None of the " + MAX_ALLOCATION_ATTEMPTS + " candidate folder names for " +
			"catalog `" + catalogName + "` could be created under `" + location + "`, although the storage " +
			"directory itself is writable - the candidates are most likely occupied by folders awaiting deletion.";
		final String publicMessage = "No usable storage folder could be allocated for catalog `" + catalogName + "`!";
		return lastFailure == null ?
			new UnexpectedIOException(privateMessage, publicMessage) :
			new UnexpectedIOException(privateMessage, publicMessage, lastFailure);
	}

}
