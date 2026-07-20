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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * A flush-time instruction to REMOVE the root {@link AttributeIndexStoragePart} of an attribute sub-index that vanished
 * this commit — either because the individual sub-index (a filter, sort, chain, unique or cardinality structure over a
 * single attribute) was dropped by churn, or because the whole owning entity index was dropped. Because the append-only
 * OffsetIndex never overwrites in place, the disappearance of the in-memory structure must be turned into an explicit
 * removal record so the persisted root part is reclaimed.
 *
 * The part carries the sub-index `(entityIndexPrimaryKey, attributeKey, indexType)` identity rather than a pre-resolved
 * primary key: the writable {@link KeyCompressor} lives store-side, so the target primary key is resolved store-side in
 * {@link #computeUniquePartIdAndSet}. It carries no payload and is never written, so it needs no Kryo serializer.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AttributeIndexRootRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 8412093746501283746L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — identity used to resolve the dropped root's
	 * primary key store-side.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The attribute key of the sub-index — used to resolve the dropped root's primary key store-side.
	 */
	@Nonnull private final AttributeIndexKey attributeKey;
	/**
	 * The kind of attribute index (filter, sort, chain, unique or cardinality) — needed both to resolve the primary key
	 * store-side and to select the concrete removed container type.
	 */
	@Nonnull private final AttributeIndexType indexType;
	/**
	 * The resolved storage-part primary key; `null` until {@link #computeUniquePartIdAndSet} resolves it store-side.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the dropped attribute index root identified by its sub-index identity.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute key of the sub-index
	 * @param indexType             the kind of attribute index that was dropped
	 */
	public AttributeIndexRootRemoval(
		int entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeKey,
		@Nonnull AttributeIndexType indexType
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeKey = attributeKey;
		this.indexType = indexType;
		this.storagePartPK = null;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.storagePartPK;
	}

	/**
	 * @return the kind of attribute index whose root is being removed — used by the caller for de-duplication and
	 * logging
	 */
	@Nonnull
	public AttributeIndexType getIndexType() {
		return this.indexType;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = AttributeIndexStoragePart.computeUniquePartId(
			this.entityIndexPrimaryKey, this.indexType, this.attributeKey, keyCompressor
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return switch (this.indexType) {
			case FILTER -> FilterIndexStoragePart.class;
			case SORT -> SortIndexStoragePart.class;
			case CHAIN -> ChainIndexStoragePart.class;
			case UNIQUE -> UniqueIndexStoragePart.class;
			case CARDINALITY -> AttributeCardinalityIndexStoragePart.class;
			default -> throw new GenericEvitaInternalError(
				"Unexpected attribute index type: " + this.indexType
			);
		};
	}
}
