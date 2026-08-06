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

package io.evitadb.core.engine;

import io.evitadb.api.CatalogState;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.catalog.UnusableCatalog.UnusableCatalogExceptionFactory;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Everything the engine is allowed to know about catalog storage folders, in one place.
 *
 * The engine may not derive a catalog's directory (see {@link CatalogFolderId} for the boundary rule), yet it
 * still has three legitimate needs around folders: look up which folder a catalog is bound to, ask the storage
 * layer to act on a folder as a whole, and name a location in an error message. Bundling them means an engine
 * component takes one collaborator rather than three, and — more usefully — that there is a single type to
 * inspect when asking "what does the engine still know about layout?".
 *
 * The storage root is carried because it is *configuration*, not layout: reporting it beside a folder token
 * lets an operator locate a folder from an error message without the engine ever performing the join.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class CatalogFolderContext {
	/**
	 * Resolves which folder token a catalog is currently bound to.
	 */
	@Getter private final CatalogFolderResolver folderResolver;
	/**
	 * Whole-folder operations, performed by the storage layer on the engine's behalf.
	 */
	@Getter private final CatalogFolderOperations folderOperations;
	/**
	 * Configured root directory holding all catalog folders — reported in diagnostics, never joined.
	 */
	private final Path storageRoot;
	/**
	 * Draws the next folder generation for a catalog name. Backed by the engine-scoped sequence service, which
	 * is engine state rather than storage state — the storage layer only turns the number into a directory.
	 */
	private final ToIntFunction<String> generationSupplier;
	/**
	 * Folders allocated for catalogs the engine state does not reference yet, keyed by catalog name.
	 *
	 * Deliberately *not* persisted. A reservation only has to outlive the gap between materialising a folder
	 * and committing its binding, which is always within one engine run; losing it to a crash is the same
	 * outcome as the operation never having happened, and the folder it named is reclaimed by boot
	 * classification because it still wears its provisional marker.
	 */
	private final Map<String, CatalogFolderId> reservedFolders = new ConcurrentHashMap<>(8);

	/**
	 * Returns the folder token the passed catalog is currently bound to.
	 *
	 * The lookup is deliberately strict: an unbound name here is a programming error, because every path that
	 * registers a catalog records its binding in the same engine-state commit, and states arriving from an
	 * older on-disk format are translated into explicit bindings on the way in. Falling back to the catalog's
	 * own name would send reads and writes to whatever directory carries that name and report success.
	 *
	 * @param catalogName name of the catalog
	 * @return token identifying the catalog's folder
	 * @throws GenericEvitaInternalError when the catalog is not bound to any folder
	 */
	@Nonnull
	public CatalogFolderId folderIdFor(@Nonnull String catalogName) {
		final CatalogFolderId folderId = this.folderResolver.boundFolderIdFor(catalogName);
		Assert.isPremiseValid(
			folderId != null,
			() -> new GenericEvitaInternalError(
				"Catalog `" + catalogName + "` is not bound to any storage folder!"
			)
		);
		return folderId;
	}

	/**
	 * Returns the folder token to bind the passed catalog to — its current binding when it has one, the folder
	 * an in-flight operation already allocated for it when there is one, and otherwise the identity token.
	 *
	 * This is the counterpart of {@link #folderIdFor(String)} and covers exactly the moments at which a name
	 * legitimately has no binding yet. The three branches are not interchangeable:
	 *
	 * 1. **Bound** — recovery from the missing bucket lands back in the folder the catalog left, not somewhere
	 *    new. Also how the create path reads back the folder its own transition phase just allocated.
	 * 2. **Reserved** — an operation that had to materialise the folder *before* the engine state could record
	 *    it. A restore writes a whole catalog into its folder before the registering mutation is ever
	 *    dispatched, so without this branch the mutation would allocate a *second* folder and bind the catalog
	 *    to it — leaving the restored data in the first one, unreferenced, with nothing reporting a failure.
	 * 3. **Identity** — a folder discovered on disk under exactly the catalog's own name, which is the only
	 *    shape boot discovery adopts today. This branch is what step 5's adoption work replaces, at which
	 *    point discovery carries the folder it found rather than assuming the name.
	 *
	 * @param catalogName name of the catalog
	 * @return token identifying the folder the catalog is to be bound to
	 */
	@Nonnull
	public CatalogFolderId folderIdForBinding(@Nonnull String catalogName) {
		final CatalogFolderId folderId = this.folderResolver.boundFolderIdFor(catalogName);
		if (folderId != null) {
			return folderId;
		}
		final CatalogFolderId reserved = this.reservedFolders.get(catalogName);
		return reserved == null ? new CatalogFolderId(catalogName) : reserved;
	}

	/**
	 * Allocates a fresh folder for the passed catalog, marks it provisional, and reserves it under the
	 * catalog's name so the operation that later registers the catalog binds to *this* folder.
	 *
	 * Every path that materialises a catalog goes through here — create, restore and duplicate — so that a
	 * generation is drawn, the directory is created and the marker is written in one place rather than three.
	 * The reservation exists because those paths differ in *when* the engine state learns about the folder: a
	 * create records its binding in the same transition phase that allocates, while a restore populates the
	 * folder long before its registering mutation runs. Reading {@link #folderIdForBinding(String)} answers
	 * both without the caller having to know which case it is in.
	 *
	 * The caller must call {@link #completeFolder(String, CatalogFolderId)} once the folder is fully written
	 * and **before** the engine-state commit that binds it.
	 *
	 * **A failed operation needs no cleanup here.** Its reservation is simply overwritten by the next
	 * allocation for the same name — every path that materialises a catalog allocates unconditionally, so a
	 * stale reservation can never be read by anything except an allocation that is about to replace it. The
	 * folder it named is left alone deliberately: it still wears its provisional marker, so boot classification
	 * recognises it as abandoned and removes it. Deleting it here would mean succeeding on a filesystem that
	 * has just demonstrated it is misbehaving, and failing at that would replace the operation's real error
	 * with a cleanup error.
	 *
	 * @param catalogName name of the catalog the folder is being allocated for
	 * @return token naming the freshly created, still-provisional folder
	 */
	@Nonnull
	public CatalogFolderId allocateFolderFor(@Nonnull String catalogName) {
		final CatalogFolderId allocated = this.folderOperations.allocateCatalogFolder(
			catalogName, () -> this.generationSupplier.applyAsInt(catalogName)
		);
		this.reservedFolders.put(catalogName, allocated);
		return allocated;
	}

	/**
	 * Declares an allocated folder complete: clears its provisional marker and drops the reservation.
	 *
	 * **Call this before the engine-state commit that binds the catalog**, never after. The boot
	 * classification table is a first-match lookup whose rows must stay disjoint, and a folder that is both
	 * referenced and provisional matches *referenced* first — so it would be loaded while still declaring its
	 * own contents untrustworthy. Clearing first makes that overlap unreachable: a crash in the window leaves
	 * an unreferenced, marker-free folder, which classifies as unclaimed and is reported rather than touched.
	 *
	 * The reservation is dropped only after the marker is gone, so a failure to clear leaves the reservation
	 * in place and a retry still finds the same folder rather than allocating a second one.
	 *
	 * @param catalogName name of the catalog whose folder is complete
	 * @param folderId    token naming the folder, as returned by {@link #allocateFolderFor(String)}
	 */
	public void completeFolder(@Nonnull String catalogName, @Nonnull CatalogFolderId folderId) {
		this.folderOperations.clearProvisionalCatalogFolderMarker(folderId);
		this.reservedFolders.remove(catalogName, folderId);
	}

	/**
	 * Creates the placeholder standing in for a catalog that cannot be used, resolving its folder binding.
	 *
	 * @param catalogName  name of the catalog the placeholder stands for
	 * @param catalogState state to report for the unusable catalog
	 * @param cause        factory producing the exception every operation on the placeholder throws
	 * @return placeholder to be installed in the engine state
	 */
	@Nonnull
	public UnusableCatalog createUnusableCatalog(
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		@Nonnull UnusableCatalogExceptionFactory cause
	) {
		return createUnusableCatalog(catalogName, folderIdFor(catalogName), catalogState, cause);
	}

	/**
	 * Creates the placeholder standing in for a catalog that cannot be used, for a folder binding the caller
	 * already holds — used where the token was looked up earlier in the same operation and must not be
	 * re-resolved, because an intervening engine-state change could move it.
	 *
	 * @param catalogName  name of the catalog the placeholder stands for
	 * @param folderId     token identifying the catalog's folder
	 * @param catalogState state to report for the unusable catalog
	 * @param cause        factory producing the exception every operation on the placeholder throws
	 * @return placeholder to be installed in the engine state
	 */
	@Nonnull
	public UnusableCatalog createUnusableCatalog(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderId folderId,
		@Nonnull CatalogState catalogState,
		@Nonnull UnusableCatalogExceptionFactory cause
	) {
		return new UnusableCatalog(
			catalogName, catalogState, folderId, this.storageRoot, this.folderOperations, cause
		);
	}

}
