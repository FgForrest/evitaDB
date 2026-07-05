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

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Objects;

/**
 * {@link AbstractLeafPagePart} specialization for the attribute-keyed leaf-page families — {@link FilterIndexLeafPagePart},
 * {@link RangeIndexLeafPagePart}, {@link SortIndexLeafPagePart}, {@link UniqueIndexLeafPagePart} and
 * {@link ChainIndexLeafPagePart}. Their stream is identified by the sub-index pair
 * `(entityIndexPrimaryKey, attributeKey)`, resolved through a {@link LeafStreamKey} (whose {@link LeafStreamKey.StreamKind}
 * variant — plain bucket vs. {@link LeafStreamKey.StreamKind#RANGE} — the concrete subclass supplies in its
 * {@link #resolveStreamId}). A write-path page carries this identity; a read-path page leaves it `null`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class AbstractAttributeLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = -7663520879213869291L;

	/**
	 * Primary key of the owning entity index — write-path identity used to resolve the stream id store-side; `null` on a
	 * rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The attribute + index-type identity of the sub-index — write-path identity used to resolve the stream id
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final AttributeKeyWithIndexType attributeKey;

	/**
	 * WRITE-PATH constructor carrying the sub-index identity; `streamId` and primary key are resolved store-side on first
	 * {@link #computeUniquePartIdAndSet(io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 */
	protected AbstractAttributeLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence
	) {
		super(pageSequence);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeKey = attributeKey;
	}

	/**
	 * READ-PATH constructor with an already-resolved `streamId` and primary key (used when rehydrating from storage); the
	 * write-path identity is left `null`.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param storagePartPK the precomputed primary key
	 */
	protected AbstractAttributeLeafPagePart(int streamId, int pageSequence, @Nonnull Long storagePartPK) {
		super(streamId, pageSequence, storagePartPK);
		this.entityIndexPrimaryKey = null;
		this.attributeKey = null;
	}

	/**
	 * Returns {@link #getEntityIndexPrimaryKey()}, throwing when this page carries no sub-index identity (a rehydrated
	 * read-path page). {@link #resolveStreamId} implementations use this instead of dereferencing the `@Nullable` getter
	 * directly, so the non-null invariant is asserted once here rather than relied upon silently at every call site.
	 *
	 * @return the owning entity index's primary key
	 */
	protected final int getEntityIndexPrimaryKeyOrThrowException() {
		return Objects.requireNonNull(
			this.entityIndexPrimaryKey,
			"A leaf page must carry its sub-index identity to resolve the stream id!"
		);
	}

	/**
	 * Returns {@link #getAttributeKey()}, throwing when this page carries no sub-index identity (a rehydrated read-path
	 * page). Mirrors {@link #getEntityIndexPrimaryKeyOrThrowException()}.
	 *
	 * @return the attribute + index-type identity of the sub-index
	 */
	@Nonnull
	protected final AttributeKeyWithIndexType getAttributeKeyOrThrowException() {
		return Objects.requireNonNull(
			this.attributeKey,
			"A leaf page must carry its sub-index identity to resolve the stream id!"
		);
	}

}
