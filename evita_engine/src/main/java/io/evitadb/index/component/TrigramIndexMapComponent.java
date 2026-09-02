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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} adapter for the per-`(attribute, locale)` {@link TrigramIndex} map carried by
 * {@link io.evitadb.index.GlobalEntityIndex}.
 *
 * # Why it emits nothing
 *
 * A {@link TrigramIndex} is **derived state**: everything it holds is a function of the distinct values in the
 * corresponding shared value tree and of the value ids that tree already persists, so it has no on-disk footprint of
 * its own and is rebuilt from the reloaded trees at catalog load
 * (`GlobalEntityIndex.reloadPlan()`). {@link #collectModifiedStorageParts} therefore emits no storage part and
 * announces no key into the {@link EntityIndexManifest} — which {@link IndexComponent} explicitly permits — and
 * {@link #emitPersistedFootprintRemovals} keeps the default no-op, because there is nothing on disk to reclaim when
 * the owning index is dropped.
 *
 * The component exists for the other half of the {@link IndexComponent} contract: the transactional-layer lifecycle.
 * A trigram index is a {@link io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer} — it owns no diff
 * piece of its own, but its dirty flag and the `trigram -> posting` B+ tree underneath it do register layers, and
 * those have to be dropped on commit and rollback along with every other sub-index's. This is what puts it into the
 * parent index's single uniform loop rather than into a hand-rolled extra hop.
 */
public final class TrigramIndexMapComponent implements IndexComponent {

	/**
	 * Backing per-`(attribute, locale)` map owned by the parent index. Held by reference because the parent index
	 * never swaps the map instance during its lifetime — only the contents change.
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, TrigramIndex> trigramIndexes;

	/**
	 * @param trigramIndexes the wrapped per-`(attribute, locale)` map
	 */
	public TrigramIndexMapComponent(@Nonnull TransactionalMap<AttributeIndexKey, TrigramIndex> trigramIndexes) {
		this.trigramIndexes = trigramIndexes;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// derived state - see the class javadoc. Nothing is written, and nothing is announced into the manifest,
		// because announcing a key would promise a reload path that reads it back off disk.
	}

	@Override
	public void resetDirty() {
		// A trigram index does carry a dirty flag, but it is NOT flush state - it is the gate that lets an index no
		// transaction touched keep its identity through the commit merge, and it is cleared by the merge itself
		// (every merged copy is born with a fresh one). Clearing it here would be wrong rather than merely useless:
		// nothing in production calls this, but a caller that did would tell a still-uncommitted transaction that
		// its writes need not be merged.
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// TransactionalMap#removeLayer drops its own diff layer AND propagates into every value that is a
		// TransactionalStateProducer, so the layers each per-entry TrigramIndex registered are covered
		this.trigramIndexes.removeLayer(transactionalLayer);
	}

}
