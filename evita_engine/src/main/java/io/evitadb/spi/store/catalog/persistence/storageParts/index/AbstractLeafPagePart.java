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

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Objects;

/**
 * Shared skeleton for a persisted leaf page identified by the sub-index pair
 * `(entityIndexPrimaryKey, attributeKey)` and streamed via a {@link LeafStreamKey}. Factors out the
 * identity/stream-id bookkeeping byte-for-byte identical across every leaf-page part sharing this
 * identity shape ({@link FilterIndexLeafPagePart}, {@link SortIndexLeafPagePart},
 * {@link UniqueIndexLeafPagePart}, {@link RangeIndexLeafPagePart}) — only the
 * {@link LeafStreamKey} variant a subclass's stream is distinguished by (plain bucket vs.
 * {@link LeafStreamKey.StreamKind#RANGE}) and the payload column(s) differ, so those are left to
 * {@link #resolveStreamId} and the subclass itself.
 *
 * A write-path page carries the sub-index identity and resolves (and caches) `streamId` store-side
 * in {@link #computeUniquePartIdAndSet} (the engine that emits the page has no compressor); a
 * read-path page (rehydrated by the serializer) instead carries the already-known `streamId` and PK
 * and leaves the identity `null`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class AbstractLeafPagePart implements StoragePart {
	@Serial private static final long serialVersionUID = 4127590368215940173L;

	/**
	 * Sentinel for a `streamId` not yet resolved (a write-path page before
	 * {@link #computeUniquePartIdAndSet}).
	 */
	public static final int UNRESOLVED_STREAM_ID = -1;

	/**
	 * Primary key of the owning entity index — write-path identity used to resolve {@link #streamId}
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The attribute + index-type identity of the sub-index — write-path identity used to resolve
	 * {@link #streamId} store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final AttributeKeyWithIndexType attributeKey;
	/**
	 * The {@link KeyCompressor} id of the sub-index stream this page belongs to.
	 * {@link #UNRESOLVED_STREAM_ID} on a write-path page until {@link #computeUniquePartIdAndSet}
	 * resolves it from the identity; already known on a rehydrated (read-path) page.
	 */
	@Getter private int streamId;
	/**
	 * The advance-only, never-reused page sequence of this leaf within its stream.
	 */
	@Getter private final int pageSequence;
	/**
	 * The storage-part primary key `pack(streamId, pageSequence)`; `null` until assigned by
	 * {@link #computeUniquePartIdAndSet(KeyCompressor)} (write path) or supplied at rehydration
	 * (read path).
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Computes the storage-part primary key for a leaf page from its resolved identifying pair.
	 *
	 * @param streamId     the resolved stream id
	 * @param pageSequence the page sequence within the stream
	 * @return the 64-bit storage-part primary key
	 */
	public static long computeUniquePartId(int streamId, int pageSequence) {
		return NumberUtils.pack(streamId, pageSequence);
	}

	/**
	 * WRITE-PATH constructor carrying the sub-index identity; `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 */
	protected AbstractLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeKey = attributeKey;
		this.streamId = UNRESOLVED_STREAM_ID;
		this.pageSequence = pageSequence;
		this.storagePartPK = null;
	}

	/**
	 * READ-PATH constructor with an already-resolved `streamId` and primary key (used when rehydrating
	 * from storage); the write-path identity is left `null`.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param storagePartPK the precomputed primary key
	 */
	protected AbstractLeafPagePart(int streamId, int pageSequence, @Nonnull Long storagePartPK) {
		this.entityIndexPrimaryKey = null;
		this.attributeKey = null;
		this.streamId = streamId;
		this.pageSequence = pageSequence;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Returns {@link #getEntityIndexPrimaryKey()}, throwing when this page carries no sub-index
	 * identity (a rehydrated read-path page). {@link #resolveStreamId} implementations use this
	 * instead of dereferencing the `@Nullable` getter directly, so the non-null invariant is asserted
	 * once here rather than relied upon silently at every call site.
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
	 * Returns {@link #getAttributeKey()}, throwing when this page carries no sub-index identity (a
	 * rehydrated read-path page). Mirrors {@link #getEntityIndexPrimaryKeyOrThrowException()}.
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

	/**
	 * Resolves this page's stream id from its `(entityIndexPrimaryKey, attributeKey)` identity via the
	 * writable compressor. Called only from {@link #computeUniquePartIdAndSet} on the write path.
	 * Implementations must read the identity through
	 * {@link #getEntityIndexPrimaryKeyOrThrowException()} / {@link #getAttributeKeyOrThrowException()}
	 * rather than the `@Nullable` getters directly. Left to the subclass because the
	 * {@link LeafStreamKey} variant a stream is distinguished by (plain bucket vs.
	 * {@link LeafStreamKey.StreamKind#RANGE}) differs per leaf-page kind.
	 *
	 * @param keyCompressor the writable compressor reached only at PK-assignment (persistence) time
	 * @return the resolved stream id
	 */
	protected abstract int resolveStreamId(@Nonnull KeyCompressor keyCompressor);

	@Override
	public final long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		if (this.streamId == UNRESOLVED_STREAM_ID) {
			// write path: resolve the stream id from the sub-index identity via the writable compressor
			// (allocates a dictionary entry on the first PAGED write of this sub-index, returns the stable
			// id thereafter); the non-null identity invariant is asserted inside resolveStreamId's
			// OrThrowException accessors
			this.streamId = resolveStreamId(keyCompressor);
		}
		final long computedUniquePartId = computeUniquePartId(this.streamId, this.pageSequence);
		if (this.storagePartPK == null) {
			this.storagePartPK = computedUniquePartId;
		} else {
			Assert.isTrue(this.storagePartPK == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

}
