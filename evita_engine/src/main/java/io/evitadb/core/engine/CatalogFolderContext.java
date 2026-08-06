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
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
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
@Slf4j
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
	 * Folders whose removal has been confirmed and whose tombstones may therefore be dropped from engine state.
	 *
	 * Also deliberately not persisted, and for the same reason: an entry only has to survive until the next
	 * engine-state commit discharges it. Losing one to a crash costs nothing, because the tombstone it would have
	 * removed names a folder that is already gone — the next boot observes that and refills the set.
	 */
	private final Set<CatalogFolderId> drainedFolders = ConcurrentHashMap.newKeySet(8);

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
	 * Takes ownership of a folder that arrived from outside, renaming it into the shape the engine allocates and
	 * reserving it so the mutation that registers the catalog binds to it.
	 *
	 * Unlike {@link #allocateFolderFor(String)} this creates nothing — the data is already there, put there by an
	 * operator or by an older evitaDB version, and adoption's whole job is to start referring to it. The rename is
	 * cosmetic and may fail without consequence; whichever token comes back is the one reserved, so a folder that
	 * could not be moved is simply bound under its bare name.
	 *
	 * Callable only at boot, before any catalog is opened. Every handle into the folder is closed at that point,
	 * which is what makes moving a directory a reasonable thing to do at all — and it is why no `completeFolder`
	 * call follows: an adopted folder never wore a provisional marker, because nothing was ever mid-write in it.
	 *
	 * The name marker is refreshed *before* the rename rather than after. A crash between the two leaves a folder
	 * that still carries its original name and now says which catalog it holds, which is strictly more recoverable
	 * than the reverse.
	 *
	 * @param catalogName       name the catalog is to be registered under
	 * @param discoveredFolder  token naming the folder as boot classification found it
	 * @return token the catalog is to be bound to
	 */
	@Nonnull
	public CatalogFolderId adoptFolderFor(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderId discoveredFolder
	) {
		this.folderOperations.recordCatalogNameInFolder(discoveredFolder, catalogName);
		final CatalogFolderId adopted = this.folderOperations.adoptCatalogFolder(
			discoveredFolder, catalogName, () -> this.generationSupplier.applyAsInt(catalogName)
		);
		this.reservedFolders.put(catalogName, adopted);
		return adopted;
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
	 * Labelling the folder with its catalog's name comes last, and cannot fail the call — see
	 * {@link #recordCatalogName(String, CatalogFolderId)}.
	 *
	 * @param catalogName name of the catalog whose folder is complete
	 * @param folderId    token naming the folder, as returned by {@link #allocateFolderFor(String)}
	 */
	public void completeFolder(@Nonnull String catalogName, @Nonnull CatalogFolderId folderId) {
		this.folderOperations.clearProvisionalCatalogFolderMarker(folderId);
		this.reservedFolders.remove(catalogName, folderId);
		recordCatalogName(catalogName, folderId);
	}

	/**
	 * Labels a folder with the name of the catalog whose data it holds, for whoever reads the storage directory
	 * without a server to ask.
	 *
	 * Folder names are cosmetic and go stale the moment a catalog is renamed, so the label is what keeps a bare
	 * storage directory interpretable during disaster recovery. Nothing in the engine reads it back — the engine
	 * state is the sole authority on where a catalog lives — which is exactly why writing it is best-effort and
	 * never throws: failing a catalog operation over a file only humans read would be the wrong trade.
	 *
	 * Call it wherever a binding is established or moved. `completeFolder` covers the paths that materialise a
	 * folder first; a path that binds an existing folder calls it directly.
	 *
	 * @param catalogName name of the catalog the folder holds
	 * @param folderId    token naming the folder
	 */
	public void recordCatalogName(@Nonnull String catalogName, @Nonnull CatalogFolderId folderId) {
		this.folderOperations.recordCatalogNameInFolder(folderId, catalogName);
	}

	/**
	 * Deletes a folder the engine state has already tombstoned, and remembers the deletion when it succeeds.
	 *
	 * Call this **after** the commit that retired the folder, never before: until that commit is durable the
	 * folder is still the live home of a catalog, and a delete that races ahead of it destroys data the engine
	 * still points at. Everything about the call is best-effort by design — a folder the operating system refuses
	 * to remove (an open handle on Windows, a transient I/O failure) leaves the tombstone in place, and the boot
	 * drain retries it. That is the whole reason the tombstone exists, so a failure here is logged rather than
	 * propagated: the operation the user asked for has already succeeded.
	 *
	 * @param folderId folder to remove
	 */
	public void deleteRetiredFolder(@Nonnull CatalogFolderId folderId) {
		try {
			this.folderOperations.dropCatalogFolder(folderId);
			noteFolderDrained(folderId);
		} catch (RuntimeException ex) {
			log.warn(
				"Failed to remove retired storage folder `{}` — it stays tombstoned and the next boot retries it.",
				folderId.id(), ex
			);
		}
	}

	/**
	 * Records that a folder is confirmed gone, so the next engine-state commit drops its tombstone.
	 *
	 * Called both by {@link #deleteRetiredFolder(CatalogFolderId)} and by the boot drain, which removes the
	 * folders a previous run could not — and, equally, observes tombstones whose folders are already absent
	 * because the deletion succeeded but no commit followed it.
	 *
	 * @param folderId folder confirmed to be gone
	 */
	public void noteFolderDrained(@Nonnull CatalogFolderId folderId) {
		this.drainedFolders.add(folderId);
	}

	/**
	 * Returns the folders confirmed gone whose tombstones are still carried by the engine state.
	 *
	 * @return live view of the confirmed-gone set; never null
	 */
	@Nonnull
	public Set<CatalogFolderId> getDrainedFolders() {
		return this.drainedFolders;
	}

	/**
	 * Forgets the folders whose tombstones a commit has just dropped.
	 *
	 * Called only once the pruned state is durable. Forgetting earlier would lose the pruning if the commit
	 * failed; not forgetting at all would grow the set for the lifetime of the run, and re-pruning an already
	 * absent tombstone is a no-op rather than an error.
	 *
	 * @param folderIds folders whose tombstones are provably no longer in persisted state
	 */
	public void forgetDrainedFolders(@Nonnull Set<CatalogFolderId> folderIds) {
		this.drainedFolders.removeAll(folderIds);
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
