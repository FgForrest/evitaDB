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
 * A flush-time instruction to REMOVE the root {@link HierarchyIndexStoragePart} of a hierarchy sub-index that vanished
 * this commit — either because the individual hierarchy structure was dropped by churn, or because the whole owning
 * entity index was dropped. Because the append-only OffsetIndex never overwrites in place, the disappearance of the
 * in-memory structure must be turned into an explicit removal record so the persisted root part is reclaimed.
 *
 * Unlike the other sub-index roots, the hierarchy root's primary key is simply the entity index primary key itself, so
 * no {@link KeyCompressor} is consulted — {@link #computeUniquePartIdAndSet} ignores its argument, mirroring
 * {@link HierarchyIndexStoragePart#computeUniquePartIdAndSet}. It carries no payload and is never written, so it needs
 * no Kryo serializer.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HierarchyIndexRootRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 6503921847610293845L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}, which is also the primary key of the hierarchy
	 * root storage part being removed.
	 */
	private final int entityIndexPrimaryKey;

	/**
	 * Creates a removal instruction for the dropped hierarchy index root.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index (also the removed root's primary key)
	 */
	public HierarchyIndexRootRemoval(int entityIndexPrimaryKey) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) this.entityIndexPrimaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.entityIndexPrimaryKey;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return HierarchyIndexStoragePart.class;
	}
}
