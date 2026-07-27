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
 * A flush-time instruction to REMOVE a single granular
 * {@link io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex} leaf page that a leaf merge (or a
 * `PAGED -> SINGLE` collapse) dropped this commit. Removing it is necessary, not optional: the append-only OffsetIndex
 * never reclaims a record that is neither superseded (page sequences are advance-only and never re-keyed) nor explicitly
 * removed, so an unreferenced leaf page would otherwise be copied forward by every compaction forever.
 *
 * Like {@link ReferenceTypeCardinalityIndexLeafPagePart} on the write path, this carries the sub-index
 * `(entityIndexPrimaryKey, referenceName)` identity rather than a pre-resolved `streamId`: the writable
 * {@link KeyCompressor} lives store-side, so the target primary key `pack(streamId, pageSequence)` is resolved store-side
 * in {@link #computeUniquePartIdAndSet} (the `streamId` is already registered — the page existed before it was freed). The
 * part carries no payload and is never written, so it needs no Kryo serializer; the flush drain resolves its key and
 * removes the leaf page.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceTypeCardinalityIndexLeafPageRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 7390418265039471826L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — identity used to resolve the freed page's
	 * `streamId` store-side.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The reference name of the sub-index — used to resolve the freed page's `streamId` store-side.
	 */
	@Nonnull private final String referenceName;
	/**
	 * The advance-only page sequence of the freed leaf within its stream.
	 */
	private final int pageSequence;
	/**
	 * The resolved storage-part primary key `pack(streamId, pageSequence)`; `null` until
	 * {@link #computeUniquePartIdAndSet} resolves it store-side.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the freed leaf page identified by its sub-index identity and page sequence.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the sub-index
	 * @param pageSequence          the page sequence of the freed leaf
	 */
	public ReferenceTypeCardinalityIndexLeafPageRemoval(
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
		// the freed page's stream was registered when the sub-index first went PAGED, so the id resolves against the
		// existing dictionary; pack it with the page sequence into the same key the leaf page was written under
		final int streamId = keyCompressor.getId(
			new ReferenceTypeCardinalityLeafStreamKey(this.entityIndexPrimaryKey, this.referenceName)
		);
		final long computedUniquePartId =
			ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(streamId, this.pageSequence);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return ReferenceTypeCardinalityIndexLeafPagePart.class;
	}
}
