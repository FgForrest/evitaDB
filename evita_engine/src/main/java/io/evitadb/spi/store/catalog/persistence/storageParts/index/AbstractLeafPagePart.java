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
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Identity-agnostic skeleton for a persisted leaf page. Every paged index family stores its tree one leaf per record,
 * and every such record is keyed the same way: by the pair `(streamId, pageSequence)` packed into the storage-part
 * primary key via {@link NumberUtils#pack}. This base factors out that streamId/pageSequence/primary-key bookkeeping — the
 * part byte-for-byte identical across every leaf-page kind — plus the two-phase `streamId` resolution: a write-path page
 * is created without a `streamId` (it has no {@link KeyCompressor}) and resolves (and caches) it store-side on the first
 * {@link #computeUniquePartIdAndSet}; a read-path page (rehydrated by the serializer) carries the already-known `streamId`
 * and primary key.
 *
 * What a page's `streamId` is resolved FROM — the sub-index identity and the {@link LeafStreamKey} variant that
 * distinguishes its stream — differs per family and is left to {@link #resolveStreamId}. Attribute-keyed families share
 * that identity too via {@link AbstractAttributeLeafPagePart}; families keyed otherwise (by histogram name + locale, by
 * reference name, …) extend this base directly and carry their own identity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class AbstractLeafPagePart implements StoragePart {
	@Serial private static final long serialVersionUID = 4127590368215940173L;

	/**
	 * Sentinel for a `streamId` not yet resolved (a write-path page before {@link #computeUniquePartIdAndSet}).
	 */
	public static final int UNRESOLVED_STREAM_ID = -1;

	/**
	 * The {@link KeyCompressor} id of the sub-index stream this page belongs to. {@link #UNRESOLVED_STREAM_ID} on a
	 * write-path page until {@link #computeUniquePartIdAndSet} resolves it from the identity; already known on a
	 * rehydrated (read-path) page.
	 */
	@Getter private int streamId;
	/**
	 * The advance-only, never-reused page sequence of this leaf within its stream.
	 */
	@Getter private final int pageSequence;
	/**
	 * The storage-part primary key `pack(streamId, pageSequence)`; `null` until assigned by
	 * {@link #computeUniquePartIdAndSet(KeyCompressor)} (write path) or supplied at rehydration (read path).
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
	 * WRITE-PATH constructor: `streamId` and primary key are resolved store-side on first
	 * {@link #computeUniquePartIdAndSet(KeyCompressor)} from the subclass identity.
	 *
	 * @param pageSequence the page sequence within the stream
	 */
	protected AbstractLeafPagePart(int pageSequence) {
		this.streamId = UNRESOLVED_STREAM_ID;
		this.pageSequence = pageSequence;
		this.storagePartPK = null;
	}

	/**
	 * READ-PATH constructor with an already-resolved `streamId` and primary key (used when rehydrating from storage).
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param storagePartPK the precomputed primary key
	 */
	protected AbstractLeafPagePart(int streamId, int pageSequence, @Nonnull Long storagePartPK) {
		this.streamId = streamId;
		this.pageSequence = pageSequence;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Resolves this page's stream id from its subclass identity via the writable compressor. Called only from
	 * {@link #computeUniquePartIdAndSet} on the write path (on a page whose `streamId` is still
	 * {@link #UNRESOLVED_STREAM_ID}). Left to the subclass because both the identity and the {@link LeafStreamKey}
	 * variant a stream is distinguished by differ per leaf-page kind.
	 *
	 * @param keyCompressor the writable compressor reached only at PK-assignment (persistence) time
	 * @return the resolved stream id
	 */
	protected abstract int resolveStreamId(@Nonnull KeyCompressor keyCompressor);

	@Override
	public final long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		if (this.streamId == UNRESOLVED_STREAM_ID) {
			// write path: resolve the stream id from the sub-index identity via the writable compressor (allocates a
			// dictionary entry on the first PAGED write of this sub-index, returns the stable id thereafter); the
			// non-null identity invariant is asserted inside the subclass resolveStreamId
			this.streamId = resolveStreamId(keyCompressor);
		}
		final long computedUniquePartId = StoragePart.verifyUniquePartId(
			computeUniquePartId(this.streamId, this.pageSequence), this.storagePartPK
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

}
