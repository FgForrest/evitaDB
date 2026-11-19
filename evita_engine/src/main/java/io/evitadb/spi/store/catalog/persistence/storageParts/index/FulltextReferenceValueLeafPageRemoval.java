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

import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serial;
import java.util.Locale;

/**
 * Removal of a {@link FulltextReferenceValueLeafPagePart} whose leaf left the dictionary - merged into a sibling, or
 * replaced by the two halves of a split - or whose whole index left: dropped from the entity index, dropped with it,
 * or replaced by a new index that does not overwrite the page. Never serialized: the flush resolves it to the removed
 * page's primary key and drops that page.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextReferenceValueLeafPageRemoval implements DeferredRemovalStoragePart {
	/** Identity of this storage-part format. */
	@Serial private static final long serialVersionUID = -8871649734450091822L;

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
	/** Immutable owning generation. */
	private final int fieldId;
	/** Child stream identity. */
	@Nonnull private final ChildKind childKind;
	/**
	 * The resolved primary key `pack(streamId, pageSequence)`; `null` until resolved.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates the removal of the reference value child page of the passed sequence.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param locale                the locale of the partition the index serves
	 * @param fieldId stable field generation
	 * @param childKind child identity
	 * @param pageSequence          the page sequence of the removed page
	 */
	public FulltextReferenceValueLeafPageRemoval(int entityIndexPrimaryKey, @Nonnull Locale locale,
		int fieldId, @Nonnull ChildKind childKind, int pageSequence) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.locale = locale;
		this.fieldId = fieldId;
		this.childKind = childKind;
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
			new FulltextReferenceValueStreamKey(this.entityIndexPrimaryKey, this.locale, this.fieldId, this.childKind)
		);
		final long computedUniquePartId = AbstractLeafPagePart.computeUniquePartId(streamId, this.pageSequence);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return FulltextReferenceValueLeafPagePart.class;
	}

}
