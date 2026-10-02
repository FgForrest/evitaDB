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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey.StreamKind;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * Removal of a {@link FulltextDictionaryLeafPagePart} whose leaf left the dictionary - merged into a sibling, or
 * replaced by the two halves of a split. Never serialized: the flush resolves it to the removed page's primary key
 * and drops that page.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextDictionaryLeafPageRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 2934403424161562499L;

	/**
	 * Primary key of the owning entity index.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The locale of the partition the index serves.
	 */
	@Nonnull private final Locale locale;
	/**
	 * The page sequence of the removed page.
	 */
	private final int pageSequence;
	/**
	 * The resolved primary key `pack(streamId, pageSequence)`; `null` until resolved.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates the removal of the dictionary page of the passed sequence.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param locale                the locale of the partition the index serves
	 * @param pageSequence          the page sequence of the removed page
	 */
	public FulltextDictionaryLeafPageRemoval(int entityIndexPrimaryKey, @Nonnull Locale locale, int pageSequence) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
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
		// the stream was registered when its first page was written, so the id resolves against the existing entry
		final int streamId = keyCompressor.getId(
			new FulltextLeafStreamKey(this.entityIndexPrimaryKey, this.locale, StreamKind.DICTIONARY)
		);
		final long computedUniquePartId = AbstractLeafPagePart.computeUniquePartId(streamId, this.pageSequence);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return FulltextDictionaryLeafPagePart.class;
	}

}
