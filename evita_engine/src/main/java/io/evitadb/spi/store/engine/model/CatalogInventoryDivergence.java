/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.spi.store.engine.model;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;

/**
 * Captures the divergence between the catalog inventory recorded in the persisted {@link EngineState} and the
 * catalog inventory the backing store currently reports, detected during engine persistence-service construction.
 *
 * The record is computed as a pure value during boot — no bootstrap is rewritten in place. It is then drained by
 * `Evita` after the `EngineTransactionManager` is available, by emitting one engine mutation per entry through the
 * regular WAL-backed apply path. Each emission produces a WAL record and bumps the engine state version, which
 * keeps the WAL-first invariant intact and makes the boot-time reconciliation observable through CDC.
 *
 * Three categories are tracked:
 *
 * - **becomeMissing** — names previously registered in `activeCatalogs` or `inactiveCatalogs` whose underlying
 *   storage is gone. Drained as `MarkCatalogMissingMutation`.
 * - **reappeared** — names previously listed in `missingCatalogs` whose storage has reappeared. Drained as
 *   `RestoreCatalogSchemaMutation` (the operator removes them from the missing bucket and registers them as
 *   `INACTIVE`).
 * - **autoDiscovered** — folders present on disk that no catalog is bound to and that classification found
 *   adoptable. Drained as `RestoreCatalogSchemaMutation` (the operator registers them as `INACTIVE`).
 *
 * `drainedFolders` sits apart from those three: it produces no mutation of its own. It reports the tombstoned
 * folders boot has confirmed are gone — either because the boot drain removed them, or because they were already
 * absent — so that the next engine-state commit can discharge their tombstones. Nothing else ever would: a folder
 * that no longer exists is never classified again, so an entry left behind would be carried in persisted state for
 * the lifetime of the installation. It is deliberately excluded from {@link #isEmpty()}, which answers "is there
 * anything to *drain through the mutation path*" and governs whether that loop runs at all.
 *
 * `autoDiscovered` carries the folder token beside the name rather than the name alone. The two coincide today —
 * only a suffix-free folder is adoptable, and the name is read from the folder — but deriving one from the other
 * is precisely the assumption this line of work removes, and the derivation would silently break the moment
 * adoption starts taking the catalog name from the folder's own header instead of its directory name.
 *
 * All lists are deterministically ordered (alphabetically) so the divergence record itself is reproducible across
 * boots over the same backing-store inventory. The WAL trail produced by draining is only partially ordered:
 * `becomeMissing` mutations are applied (and committed to the WAL) before any restore is dispatched — Phase 1 is
 * awaited to completion before Phase 2 begins. `reappeared` and `autoDiscovered` are then dispatched in parallel
 * (they operate on disjoint name sets), so their relative WAL order is non-deterministic. See `Evita`'s drain loop
 * for the rationale.
 *
 * @param becomeMissing  catalogs to mark as missing, alphabetically ordered; never null
 * @param reappeared     catalogs to move from missing back to inactive, alphabetically ordered; never null
 * @param autoDiscovered folders offered for adoption, ordered by catalog name; never null
 * @param drainedFolders tombstoned folders confirmed gone, whose tombstones may be dropped; never null
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
public record CatalogInventoryDivergence(
	@Nonnull List<String> becomeMissing,
	@Nonnull List<String> reappeared,
	@Nonnull List<AdoptableCatalogFolder> autoDiscovered,
	@Nonnull List<CatalogFolderId> drainedFolders
) {
	/**
	 * Shared empty divergence — used by services that detect no inventory divergence at boot.
	 */
	public static final CatalogInventoryDivergence EMPTY = new CatalogInventoryDivergence(
		List.of(), List.of(), List.of(), List.of()
	);

	/**
	 * Defensive copies guarantee immutability irrespective of the caller's list type.
	 */
	public CatalogInventoryDivergence(
		@Nonnull List<String> becomeMissing,
		@Nonnull List<String> reappeared,
		@Nonnull List<AdoptableCatalogFolder> autoDiscovered,
		@Nonnull List<CatalogFolderId> drainedFolders
	) {
		this.becomeMissing = List.copyOf(becomeMissing);
		this.reappeared = List.copyOf(reappeared);
		this.autoDiscovered = List.copyOf(autoDiscovered);
		this.drainedFolders = List.copyOf(drainedFolders);
	}

	/**
	 * Returns `true` when there is nothing to drain — the engine state already matches the backing store's inventory.
	 */
	public boolean isEmpty() {
		return this.becomeMissing.isEmpty() && this.reappeared.isEmpty() && this.autoDiscovered.isEmpty();
	}
}
