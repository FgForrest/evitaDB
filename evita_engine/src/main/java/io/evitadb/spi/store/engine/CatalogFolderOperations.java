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

		};
	}

}
