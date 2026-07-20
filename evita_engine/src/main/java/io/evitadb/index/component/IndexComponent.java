/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;

import javax.annotation.Nonnull;

/**
 * A self-contained piece of an {@link io.evitadb.index.EntityIndex} that knows how to:
 *
 * 1. emit its own modified storage parts into the supplied {@link TrappedChanges},
 * 2. announce the keys it owns into the shared {@link EntityIndexManifest} so the parent
 *    {@link io.evitadb.index.EntityIndex} can build a single coherent
 *    {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart}
 *    listing every sub-index that should be reloaded on catalog restart,
 * 3. clear its own dirty state once changes have been persisted,
 * 4. drop transactional layers it has registered with the
 *    {@link TransactionalLayerMaintainer} on commit/rollback.
 *
 * This abstraction replaces the previously hand-rolled per-sub-index method calls that
 * `EntityIndex` had to coordinate. Each large sub-system (`AttributeIndex`, `HierarchyIndex`,
 * `FacetIndex`, `PriceIndex`) registers either itself or a thin adapter as an
 * `IndexComponent`, and the parent index walks the registered components in a single
 * uniform loop.
 *
 * Components must be allocation-free in the flush path: the parent index calls
 * `collectModifiedStorageParts` on every commit, so allocations here are amplified by the
 * number of dirty indexes in the catalog.
 */
public interface IndexComponent {

	/**
	 * Emits every storage part this component owns that has changed since the last
	 * `resetDirty()` into `trappedChanges`, and announces the keys this component
	 * currently owns into the shared `manifest`. The parent index uses the manifest
	 * to decide whether a fresh
	 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart}
	 * needs to be written.
	 *
	 * Implementations must not throw on a clean component — they must emit nothing
	 * and contribute nothing to the manifest when there is no live data to advertise.
	 *
	 * @param entityIndexPrimaryKey the primary key of the owning entity index, used by
	 *                              sub-index storage parts as the foreign key linking
	 *                              them back to the parent
	 * @param manifest the shared manifest into which this component announces the keys
	 *                 it currently owns; multiple components write into the same instance
	 * @param trappedChanges the accumulator that collects modified storage parts for the
	 *                       current commit
	 */
	void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	);

	/**
	 * Clears the dirty state of this component after a successful flush. Mirrors
	 * the contract of {@link io.evitadb.index.IndexDataStructure#resetDirty()}.
	 */
	void resetDirty();

	/**
	 * Removes any transactional memory layers this component registered with the
	 * given {@link TransactionalLayerMaintainer}. Called from the parent
	 * `EntityIndex.removeTransactionalMemoryOfReferencedProducers` /
	 * `removeLayer` paths during commit and rollback to prevent orphaned layers.
	 *
	 * @param transactionalLayer the layer maintainer whose entries should be dropped
	 */
	void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);

	/**
	 * Emits removal instructions reclaiming this component's ENTIRE persisted footprint when the owning
	 * {@link io.evitadb.index.EntityIndex} is dropped: every persisted leaf page, and any root part addressed
	 * independently of the {@link EntityIndexManifest} (e.g. the reference-type cardinality root, which the
	 * manifest-baseline diff in {@code EntityIndex.emitVanishedRootRemovals} does not cover). Manifest-listed
	 * roots (attribute / price / facet / histogram / hierarchy) are reclaimed by that diff and MUST NOT be
	 * re-emitted here. Implementations read only their persisted baseline, never live/transactional state.
	 *
	 * The default is a no-op — correct for components that persist no leaf pages and whose root (if any) is
	 * manifest-listed (facet, hierarchy, attribute cardinality).
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param trappedChanges        the accumulator collecting the removal instructions
	 */
	default void emitPersistedFootprintRemovals(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		// no persisted leaf pages and no non-manifest root — nothing to reclaim
	}

}
