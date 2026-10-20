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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * A flush-time instruction to remove one {@link VirtualReferencePartitionIndexLeafPagePart} that is no longer part of
 * its index - dropped by a leaf merge, by a collapse to the inline shape, by the index becoming empty, or by the
 * whole owning entity index being dropped. The append-only offset index reclaims only records it is told to remove,
 * so an unreferenced page would otherwise be copied forward by every compaction.
 *
 * The instruction carries the `(entityIndexPrimaryKey, referenceName)` identity of the index instead of a resolved
 * primary key, because the key compressor lives store-side; the key is resolved in {@link #computeUniquePartIdAndSet}.
 * It carries no payload and is never written, so it has no serializer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class VirtualReferencePartitionIndexLeafPageRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = -9116276441460456542L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference of the index.
	 */
	@Nonnull private final String referenceName;
	/**
	 * The page sequence of the removed leaf within its stream.
	 */
	private final int pageSequence;
	/**
	 * The resolved primary key `pack(streamId, pageSequence)`; `null` until {@link #computeUniquePartIdAndSet}.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the leaf page identified by the identity of its index and its page sequence.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the index
	 * @param pageSequence          the page sequence of the removed leaf
	 */
	public VirtualReferencePartitionIndexLeafPageRemoval(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		int pageSequence
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.pageSequence = pageSequence;
		this.storagePartPK = null;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.storagePartPK;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		// the stream was registered when the page was first written, so the id resolves against the existing dictionary
		final int streamId = keyCompressor.getId(
			new VirtualReferencePartitionLeafStreamKey(this.entityIndexPrimaryKey, this.referenceName)
		);
		final long computedUniquePartId = AbstractLeafPagePart.computeUniquePartId(streamId, this.pageSequence);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return VirtualReferencePartitionIndexLeafPagePart.class;
	}
}
