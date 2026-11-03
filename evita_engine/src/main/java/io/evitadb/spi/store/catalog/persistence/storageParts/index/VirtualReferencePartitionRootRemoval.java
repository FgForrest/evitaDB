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
import java.util.OptionalInt;

/**
 * A flush-time instruction to remove the root {@link VirtualReferencePartitionIndexStoragePart} of an index that
 * became empty, or whose owning entity index was dropped.
 *
 * The instruction carries the `(entityIndexPrimaryKey, referenceName)` identity of the index instead of a resolved
 * primary key, because the key compressor lives store-side; the key is resolved in {@link #computeUniquePartIdAndSet}.
 * It carries no payload and is never written, so it has no serializer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class VirtualReferencePartitionRootRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 3478134433480306331L;
	/**
	 * Primary key handed back when the reference name has no dictionary entry, which proves the root was never
	 * written. Negative by construction, so it never matches a real `pack(entityIndexPrimaryKey, id)` key and the
	 * removal it drives removes nothing.
	 */
	private static final long NEVER_PERSISTED_PART_ID = -1L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference of the index.
	 */
	@Nonnull private final String referenceName;
	/**
	 * The resolved primary key; `null` until {@link #computeUniquePartIdAndSet}.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the root of the index identified by its owning entity index and reference.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the index
	 */
	public VirtualReferencePartitionRootRemoval(int entityIndexPrimaryKey, @Nonnull String referenceName) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.storagePartPK = null;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.storagePartPK;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		// the removal is emitted whenever the owning entity index is dropped, whether or not the root ever reached
		// disk, and the drain resolves it against the read-only compressor, which cannot mint an id - an absent
		// reference name proves the root was never written, so the removal resolves to a key that matches no record
		final OptionalInt referenceNameId = keyCompressor.getIdIfExists(new ReferenceNameKey(this.referenceName));
		final long computedUniquePartId = referenceNameId.isPresent() ?
			VirtualReferencePartitionIndexStoragePart.computeUniquePartId(
				this.entityIndexPrimaryKey, referenceNameId.getAsInt()
			) :
			NEVER_PERSISTED_PART_ID;
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return VirtualReferencePartitionIndexStoragePart.class;
	}
}
