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
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * One persisted leaf page of the entry tree of a {@link io.evitadb.index.VirtualReferencePartitionIndex}. Each leaf is
 * stored as its own record, so a flush rewrites only the leaves it changed rather than every entry of the reference.
 *
 * The page holds two positionally-aligned primitive columns: the `pack(slotId, partitionPrimaryKey)` keys in ascending
 * order and their `long` payloads.
 *
 * The `(streamId, pageSequence)` identity and primary-key packing are inherited from {@link AbstractLeafPagePart}. A
 * write-path page carries the `(entityIndexPrimaryKey, referenceName)` identity of its index and resolves the stream
 * id store-side through {@link VirtualReferencePartitionLeafStreamKey}; a read-path page carries the already-resolved
 * stream id and leaves the identity `null`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class VirtualReferencePartitionIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = -7838055751745929503L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} - the write-path identity used to resolve the
	 * stream id; `null` on a read-path page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Name of the reference of the index - the write-path identity used to resolve the stream id; `null` on a
	 * read-path page.
	 */
	@Nullable @Getter private final String referenceName;
	/**
	 * The leaf's keys in ascending order, positionally aligned with {@link #payloads}.
	 */
	@Nonnull @Getter private final long[] keys;
	/**
	 * The payload of each key, positionally aligned with {@link #keys}.
	 */
	@Nonnull @Getter private final long[] payloads;

	/**
	 * Creates a write-path leaf page carrying the identity of its index; the stream id and primary key are resolved
	 * store-side on the first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference name of the index
	 * @param pageSequence          the page sequence within the stream
	 * @param keys                  the leaf's keys in ascending order
	 * @param payloads              the payload of each key, aligned with `keys`
	 */
	public VirtualReferencePartitionIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		int pageSequence,
		@Nonnull long[] keys,
		@Nonnull long[] payloads
	) {
		super(pageSequence);
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.keys = keys;
		this.payloads = payloads;
	}

	/**
	 * Creates a read-path leaf page with an already-resolved stream id and primary key.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param keys          the leaf's keys in ascending order
	 * @param payloads      the payload of each key, aligned with `keys`
	 * @param storagePartPK the precomputed primary key
	 */
	public VirtualReferencePartitionIndexLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		this.entityIndexPrimaryKey = null;
		this.referenceName = null;
		this.keys = keys;
		this.payloads = payloads;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		Assert.isPremiseValid(
			this.entityIndexPrimaryKey != null && this.referenceName != null,
			"A leaf page must carry the identity of its index to resolve the stream id!"
		);
		return keyCompressor.getId(
			new VirtualReferencePartitionLeafStreamKey(this.entityIndexPrimaryKey, this.referenceName)
		);
	}
}
