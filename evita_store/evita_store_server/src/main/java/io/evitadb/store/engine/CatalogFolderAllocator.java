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
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
