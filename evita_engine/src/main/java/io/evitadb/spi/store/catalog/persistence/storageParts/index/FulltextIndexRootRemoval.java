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
import java.util.Locale;

/**
 * Removal of the {@link FulltextIndexStoragePart} root of a fulltext index that vanished - its locale dropped out of
 * the owning entity index, or the whole entity index was dropped. The manifest lists the root by its
 * {@link FulltextIndexKey}, so the manifest-baseline diff in {@link io.evitadb.index.EntityIndex} is what emits this;
 * the index's dictionary pages and length blocks are reclaimed by their own removals. Never serialized: the flush
 * resolves it to the root's primary key store-side, where the writable {@link KeyCompressor} lives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextIndexRootRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 4352290641924590970L;

	/**
	 * Primary key of the owning entity index.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The locale of the partition the removed index served.
	 */
	@Nonnull private final Locale locale;
	/**
	 * The resolved primary key `pack(entityIndexPrimaryKey, id of the FulltextIndexKey)`; `null` until resolved.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates the removal of the root of the fulltext index of the passed locale.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param locale                the locale of the partition the removed index served
	 */
	public FulltextIndexRootRemoval(int entityIndexPrimaryKey, @Nonnull Locale locale) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.storagePartPK = null;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.storagePartPK;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = FulltextIndexStoragePart.computeUniquePartId(
			this.entityIndexPrimaryKey, this.locale, keyCompressor
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return FulltextIndexStoragePart.class;
	}

}
