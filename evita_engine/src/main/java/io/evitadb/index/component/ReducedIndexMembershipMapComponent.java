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
import io.evitadb.index.membership.ReducedIndexMembership;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} adapter for the per-reference {@link ReducedIndexMembership} map carried by
 * {@link io.evitadb.index.GlobalEntityIndex}.
 *
 * # Why it emits nothing
 *
 * A {@link ReducedIndexMembership} is **derived state**: every entry it holds is a function of the membership
 * bitmaps of the collection's reduced indexes, which are persisted in their own right. It has no on-disk
 * footprint of its own and is rebuilt when the collection is loaded, so {@link #collectModifiedStorageParts}
 * emits no storage part and announces no key into the {@link EntityIndexManifest} — which
 * {@link IndexComponent} explicitly permits — and {@link #emitPersistedFootprintRemovals} keeps the default
 * no-op, because there is nothing on disk to reclaim when the owning index is dropped.
 *
 * Keeping it out of storage is deliberate rather than incidental: the map is an accelerator whose absence
 * costs only speed, so persisting it would buy a faster first trigger at the price of a storage format
 * change and a reload path that could disagree with the indexes it summarises.
 *
 * # Why it exists at all
 *
 * For the other half of the {@link IndexComponent} contract: the transactional-layer lifecycle. A
 * {@link ReducedIndexMembership} is a
 * {@link io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer} — it owns no diff piece of its
 * own, but the map and the three bitmaps underneath it do register layers, and those have to be dropped on
 * commit and rollback along with every other sub-index's. This is what puts it into the parent index's
 * single uniform loop rather than into a hand-rolled extra hop.
 *
 * @author Claude (issue #1529 sibling-resolver optimization), FG Forrest a.s. (c) 2026
 */
public final class ReducedIndexMembershipMapComponent implements IndexComponent {

	/**
	 * Backing per-reference map owned by the parent index. Held by reference because the parent index never
	 * swaps the map instance during its lifetime — only the contents change.
	 */
	@Nonnull private final TransactionalMap<String, ReducedIndexMembership> membershipByReference;

	/**
	 * @param membershipByReference the wrapped per-reference membership map
	 */
	public ReducedIndexMembershipMapComponent(
		@Nonnull TransactionalMap<String, ReducedIndexMembership> membershipByReference
	) {
		this.membershipByReference = membershipByReference;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// derived state - see the class javadoc. Nothing is written, and nothing is announced into the
		// manifest, because announcing a key would promise a reload path that reads it back off disk.
	}

	@Override
	public void resetDirty() {
		// nothing to reset - this component contributes no flush state, because it contributes no storage part
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// TransactionalMap#removeLayer drops its own diff layer AND propagates into every value that is a
		// TransactionalStateProducer, so the layers each per-reference membership registered are covered
		this.membershipByReference.removeLayer(transactionalLayer);
	}

}
