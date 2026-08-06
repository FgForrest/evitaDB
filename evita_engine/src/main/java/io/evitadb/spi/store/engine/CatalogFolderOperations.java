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

package io.evitadb.spi.store.engine;

import io.evitadb.spi.store.engine.model.CatalogFolderId;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Whole-folder operations the engine needs to perform on a catalog's storage folder without knowing where —
 * or what — that folder is.
 *
 * The engine binds a catalog to an opaque {@link CatalogFolderId} and can no longer join that token onto the
 * storage root itself (see issue #649). It nevertheless legitimately needs to ask three questions about the
 * folder as a whole, so those are named here and answered by the storage layer, which is the only side that
 * knows a token denotes a directory.
 *
 * This is deliberately *not* on `CatalogPersistenceServiceFactory`, which stays a pure "open or create a
 * catalog by token" contract. Folder lifecycle is a topology concern and belongs beside the engine state that
 * records it — hence {@link EnginePersistenceService} extends this interface. It is also the surface the
 * boot-time folder classification and the tombstone drain will grow into.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface CatalogFolderOperations {

	/**
	 * Tells whether the folder the passed token denotes currently exists.
	 *
	 * @param folderId token identifying the catalog folder
	 * @return true when the folder is present on the underlying storage
	 */
	boolean catalogFolderExists(@Nonnull CatalogFolderId folderId);

	/**
	 * Removes the folder the passed token denotes, along with everything inside it.
	 *
	 * Removing a folder that does not exist is a no-op rather than an error — the caller's intent is that the
	 * folder is gone afterwards, and a crash between a first and a second attempt must not turn a completed
	 * removal into a failure.
	 *
	 * @param folderId token identifying the catalog folder
	 */
	void dropCatalogFolder(@Nonnull CatalogFolderId folderId);

	/**
	 * Computes the total size in bytes of everything stored in the folder the passed token denotes.
	 *
	 * Used to report `sizeOnDiskInBytes` for catalogs that cannot be opened, whose statistics would otherwise
	 * be unavailable entirely.
	 *
	 * @param folderId token identifying the catalog folder
	 * @return total size of the folder contents in bytes, or 0 when the folder does not exist
	 */
	long catalogFolderSize(@Nonnull CatalogFolderId folderId);

	/**
	 * Creates a fresh, empty folder for the named catalog and marks it provisional.
	 *
	 * The generation numbers come from the caller because the counter is engine state, not storage state — the
	 * storage layer only turns a number into a directory. `generationSupplier` must never hand out the same
	 * number twice within a run: a number is burned per *attempt*, so a name that cannot be created is never
	 * retried, which is what stops a folder the filesystem refuses to clear from making the catalog
	 * permanently unallocatable.
	 *
	 * The returned folder is **provisional** — it carries a marker declaring its contents untrustworthy, and
	 * boot classification will delete it if it is still unreferenced at the next start. The caller must call
	 * {@link #clearProvisionalCatalogFolderMarker(CatalogFolderId)} once the folder is fully populated and
	 * **before** the engine state commits the binding pointing at it.
	 *
	 * @param catalogName        name of the catalog the folder is allocated for; only cosmetic, never authoritative
	 * @param generationSupplier source of generation numbers, one drawn per attempt
	 * @return token naming the freshly created folder
	 */
	@Nonnull
	CatalogFolderId allocateCatalogFolder(@Nonnull String catalogName, @Nonnull IntSupplier generationSupplier);

	/**
	 * Brings a folder that arrived from outside into the shape the engine allocates, by renaming it in place.
	 *
	 * Serves the folder an operator hand-placed and the bare-name folder an older evitaDB left behind alike — on
	 * disk the two are indistinguishable, so they deliberately share one code path. May only be called at boot,
	 * before any catalog is opened: that is the one moment every handle into the folder is closed, which is what
	 * makes moving it safe rather than a coin flip.
	 *
	 * **A failed rename is not an error.** The caller binds whichever token comes back, and a folder bound under
	 * its bare name works exactly as well — it merely stays outside the generation scheme, and does so
	 * permanently: once bound it classifies as referenced, which adoption never revisits. Refusing to adopt a
	 * catalog whose data is perfectly readable would be the worse outcome by far.
	 *
	 * @param folderId           token naming the folder as it currently exists on disk
	 * @param catalogName        name of the catalog whose data the folder holds; only cosmetic, never authoritative
	 * @param generationSupplier source of generation numbers, one drawn per attempt
	 * @return token naming the folder afterwards: the renamed one on success, the original one otherwise
	 */
	@Nonnull
	CatalogFolderId adoptCatalogFolder(
		@Nonnull CatalogFolderId folderId,
		@Nonnull String catalogName,
		@Nonnull IntSupplier generationSupplier
	);

	/**
	 * Records which catalog a folder belongs to, in a marker file inside the folder itself.
	 *
	 * Best-effort by contract: implementations must not propagate a failure. Nothing in the engine reads the
	 * marker to make a decision — the engine state is the sole authority on where a catalog lives — so a folder
	 * without one is fully functional. It exists for the operator doing disaster recovery against a bare storage
	 * directory with no server to ask, and failing a catalog operation over a file only humans read would be the
	 * wrong trade.
	 *
	 * @param folderId    token identifying the catalog folder
	 * @param catalogName name of the catalog the folder holds
	 */
	void recordCatalogNameInFolder(@Nonnull CatalogFolderId folderId, @Nonnull String catalogName);

	/**
	 * Reports the highest folder generation actually present on storage, per catalog name.
	 *
	 * This is the second half of the boot seed for the generation counters, and it covers a failure the
	 * persisted peaks cannot: a folder an operation created before dying without persisting anything. The peak
	 * knows nothing of such a folder, so seeding from peaks alone would hand its number out again.
	 *
	 * The two terms are complementary rather than redundant — the peak covers the opposite case, a name that is
	 * unusable but invisible to a scan, which is why both are applied.
	 *
	 * @return highest generation seen per catalog name; names with no suffixed folder are absent, never null
	 */
	@Nonnull
	Map<String, Integer> observedFolderGenerationPeaks();

	/**
	 * Declares the contents of a folder complete by removing its provisional marker.
	 *
	 * Must run **before** the engine-state commit that binds a catalog to this folder. The reverse order leaves
	 * a referenced folder wearing an "incomplete" marker, and the boot classification table is a first-match
	 * lookup whose rows must stay disjoint — such a folder would match *referenced* and be loaded despite the
	 * marker saying it must not be. A crash in the window this ordering opens leaves an unreferenced,
	 * marker-free folder instead, which classifies as unclaimed: reported and left alone.
	 *
	 * @param folderId token identifying the catalog folder
	 */
	void clearProvisionalCatalogFolderMarker(@Nonnull CatalogFolderId folderId);

	/**
	 * Returns operations that refuse every call, for placeholders that must never perform folder work.
	 *
	 * Used where a `CatalogContract` stub is constructed purely to satisfy a signature and is not reachable by
	 * anything that could act on its folder — a refusal is preferable to handing such a stub live operations
	 * it has no business performing.
	 *
	 * @param reason explanation included in the thrown exception, naming what the stub is for
	 * @return operations throwing {@link IllegalStateException} on every method
	 */
	@Nonnull
	static CatalogFolderOperations unsupported(@Nonnull String reason) {
		return new CatalogFolderOperations() {

			@Override
			public boolean catalogFolderExists(@Nonnull CatalogFolderId folderId) {
				throw new IllegalStateException(reason);
			}

			@Override
			public void dropCatalogFolder(@Nonnull CatalogFolderId folderId) {
				throw new IllegalStateException(reason);
			}

			@Override
			public long catalogFolderSize(@Nonnull CatalogFolderId folderId) {
				throw new IllegalStateException(reason);
			}

			@Nonnull
			@Override
			public CatalogFolderId allocateCatalogFolder(
				@Nonnull String catalogName,
				@Nonnull IntSupplier generationSupplier
			) {
				throw new IllegalStateException(reason);
			}

			@Nonnull
			@Override
			public CatalogFolderId adoptCatalogFolder(
				@Nonnull CatalogFolderId folderId,
				@Nonnull String catalogName,
				@Nonnull IntSupplier generationSupplier
			) {
				throw new IllegalStateException(reason);
			}

			@Override
			public void recordCatalogNameInFolder(
				@Nonnull CatalogFolderId folderId,
				@Nonnull String catalogName
			) {
				throw new IllegalStateException(reason);
			}

			@Nonnull
			@Override
			public Map<String, Integer> observedFolderGenerationPeaks() {
				throw new IllegalStateException(reason);
			}

			@Override
			public void clearProvisionalCatalogFolderMarker(@Nonnull CatalogFolderId folderId) {
				throw new IllegalStateException(reason);
			}

		};
	}

}
