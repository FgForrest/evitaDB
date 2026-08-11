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

import io.evitadb.spi.store.engine.model.CatalogFolderId;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Exclusive claim on a freshly materialised catalog folder, held from the moment it is allocated until the engine
 * state binds a catalog to it.
 *
 * The claim is what makes {@link CatalogFolderContext#folderIdForBinding(String)} answerable. That lookup is by
 * *name* — a restore's registering mutation carries only the catalog's name, never the token its work phase wrote
 * into — so "the folder reserved for `products`" has to have exactly one answer. Before this type existed the
 * reservation map was a plain `put`, a second operation on the same name silently overwrote the first, and the
 * first then bound its catalog to the second's still-incomplete folder while its own data was left unreferenced
 * and reclaimed as an abandoned allocation.
 *
 * **The release is mandatory, and that is the whole reason this is a handle rather than a boolean.** Refusing a
 * second allocation while one is outstanding is only safe if an outstanding claim always ends: recovery from a
 * failed create or restore used to work *by overwrite*, so a refusal with no matching release would make a catalog
 * name permanently un-materialisable after its first failure. Every path that allocates must therefore close this
 * on every exit, which `try`/`finally` makes structural rather than a matter of remembering.
 *
 * Closing is idempotent, so the ordinary path — close in a `finally` that also covers the success case — needs no
 * branch. It is deliberately **not** tied to the folder having been completed: a claim outlives
 * {@link CatalogFolderContext#completeFolder(String, CatalogFolderId)} only until the caller unwinds.
 *
 * Modelled on `CatalogVersionPin`, and for the same reason: the release is bound to the map entry this claim
 * actually made, so it can never give back an entry some other operation established.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CatalogFolderReservation implements AutoCloseable {
	/**
	 * Name of the catalog the folder was materialised for.
	 */
	private final String catalogName;
	/**
	 * Token naming the folder this claim holds.
	 */
	private final CatalogFolderId folderId;
	/**
	 * Gives the claim back. Bound at acquisition to the entry this reservation made, so a release can never evict
	 * an entry belonging to a different operation.
	 */
	private final Consumer<CatalogFolderReservation> releaseAction;
	/**
	 * Guards against a second release. Releasing twice would not merely no-op — it would drop whichever claim a
	 * later operation has since established under the same name, reopening the defect this type closes.
	 */
	private final AtomicBoolean released = new AtomicBoolean();

	CatalogFolderReservation(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderId folderId,
		@Nonnull Consumer<CatalogFolderReservation> releaseAction
	) {
		this.catalogName = catalogName;
		this.folderId = folderId;
		this.releaseAction = releaseAction;
	}

	/**
	 * Returns the name of the catalog this folder was materialised for.
	 *
	 * @return the catalog name
	 */
	@Nonnull
	public String catalogName() {
		return this.catalogName;
	}

	/**
	 * Returns the token naming the folder this claim holds.
	 *
	 * @return the folder token
	 */
	@Nonnull
	public CatalogFolderId folderId() {
		return this.folderId;
	}

	/**
	 * Returns TRUE once this claim has been given back.
	 *
	 * @return TRUE when the reservation was released
	 */
	public boolean isReleased() {
		return this.released.get();
	}

	@Override
	public void close() {
		if (this.released.compareAndSet(false, true)) {
			this.releaseAction.accept(this);
		}
	}

	@Nonnull
	@Override
	public String toString() {
		return "reservation of `" + this.folderId + "` for catalog `" + this.catalogName + '`';
	}

}
