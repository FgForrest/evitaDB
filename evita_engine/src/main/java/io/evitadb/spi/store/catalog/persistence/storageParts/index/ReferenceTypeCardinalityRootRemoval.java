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
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.OptionalInt;

/**
 * A flush-time instruction to REMOVE the root {@link ReferenceTypeCardinalityIndexStoragePart} of a reference-type
 * cardinality sub-index that vanished this commit — either because the individual sub-index was dropped by churn, or
 * because the whole owning entity index was dropped. Because the append-only OffsetIndex never overwrites in place, the
 * disappearance of the in-memory structure must be turned into an explicit removal record so the persisted root part is
 * reclaimed.
 *
 * The part carries the sub-index `(entityIndexPrimaryKey, referenceName)` identity rather than a pre-resolved primary
 * key: the writable {@link KeyCompressor} lives store-side, so the target primary key is resolved store-side in
 * {@link #computeUniquePartIdAndSet}. It carries no payload and is never written, so it needs no Kryo serializer.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceTypeCardinalityRootRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 7218094653012987465L;
	/**
	 * Primary key handed back when the reference name has no dictionary entry, which proves the root was never
	 * written. Negative by construction, so it can never collide with a real `pack(entityIndexPrimaryKey, id)` key and
	 * the removal it drives resolves to nothing.
	 */
	private static final long NEVER_PERSISTED_PART_ID = -1L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — identity used to resolve the dropped root's
	 * primary key store-side.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The reference name of the sub-index — used to resolve the dropped root's primary key store-side.
	 */
	@Nonnull private final String referenceName;
	/**
	 * The resolved storage-part primary key; `null` until {@link #computeUniquePartIdAndSet} resolves it store-side.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the dropped reference-type cardinality index root identified by its sub-index
	 * identity.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the sub-index
	 */
	public ReferenceTypeCardinalityRootRemoval(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName
	) {
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
		// Unlike the manifest-listed roots — whose removal is only ever emitted for a key present in the index's
		// persisted baseline, so the dictionary is guaranteed to know it — this root contributes nothing to the
		// EntityIndexManifest and is therefore emitted unconditionally when its owning index is dropped. A cardinality
		// index that never reached disk has no dictionary entry, and the drain resolves against the READ-ONLY
		// compressor, which cannot mint one (it throws). Resolve defensively: an absent key proves the root was never
		// written, so there is nothing to reclaim and we hand back a primary key that matches no record — the
		// subsequent removal is then a documented no-op rather than a failed commit.
		final OptionalInt referenceNameId = keyCompressor.getIdIfExists(new ReferenceNameKey(this.referenceName));
		final long computedUniquePartId = referenceNameId.isPresent() ?
			NumberUtils.pack(this.entityIndexPrimaryKey, referenceNameId.getAsInt()) :
			NEVER_PERSISTED_PART_ID;
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return ReferenceTypeCardinalityIndexStoragePart.class;
	}
}
