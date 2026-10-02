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
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionRootRemoval;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} wrapper for the {@link VirtualReferencePartitionIndex} carried by
 * {@link io.evitadb.index.ReferencedTypeEntityIndex}.
 *
 * The component contributes nothing to the {@link EntityIndexManifest}: the index root is addressed by the
 * `(entityIndexPrimaryKey, referenceName)` pair of its own, and an index with no root on disk loads empty.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionComponent implements IndexComponent {

	/**
	 * The wrapped index. Held by reference because the parent index never swaps the instance during its lifetime.
	 */
	@Nonnull private final VirtualReferencePartitionIndex virtualReferencePartitionIndex;
	/**
	 * The reference name read from the parent index key's discriminator - the second half of the identity of the
	 * index's storage parts.
	 */
	@Nonnull private final String referenceName;

	/**
	 * @param virtualReferencePartitionIndex the wrapped index
	 * @param referenceName                  the reference name identifying the index's storage parts
	 */
	public VirtualReferencePartitionComponent(
		@Nonnull VirtualReferencePartitionIndex virtualReferencePartitionIndex,
		@Nonnull String referenceName
	) {
		this.virtualReferencePartitionIndex = virtualReferencePartitionIndex;
		this.referenceName = referenceName;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// the index gates on its own dirty flag, so a clean or never-written index emits nothing
		this.virtualReferencePartitionIndex.appendStorageParts(
			entityIndexPrimaryKey, this.referenceName, trappedChanges
		);
	}

	@Override
	public void announceKeys(@Nonnull EntityIndexManifest manifest) {
		// the partition root is addressed by its own storage key, not listed by the parent manifest - nothing to
		// announce
	}

	@Override
	public void emitPersistedFootprintRemovals(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		// every leaf page the index has on disk...
		this.virtualReferencePartitionIndex.emitPersistedLeafPageRemovals(
			entityIndexPrimaryKey, this.referenceName, trappedChanges
		);
		// ...and the root, which the manifest-baseline diff of the parent index cannot see because the manifest does
		// not list it. A root that never reached disk resolves to a removal of nothing
		trappedChanges.addChangeToStore(
			new VirtualReferencePartitionRootRemoval(entityIndexPrimaryKey, this.referenceName)
		);
	}

	@Override
	public void resetDirty() {
		this.virtualReferencePartitionIndex.resetDirty();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.virtualReferencePartitionIndex.removeLayer(transactionalLayer);
	}

}
