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
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * One persisted leaf page of a granular {@link io.evitadb.index.attribute.ChainIndex} value tree
 * (an {@code UnorderedLookupTree}). Under the tree-as-pages layout each leaf of the tree is stored as its own record,
 * so a transaction (re)writes only the leaf pages it actually changed instead of re-materializing the whole chain
 * state on every commit.
 *
 * The page persists ONLY non-derived facts (landmine-A): the leaf's ordered {@link #recordIds} in tree order, the
 * {@link #headWords} bitset marking which of those records are chain heads (bit `i` set == `recordIds[i]` is a head),
 * and the {@link #headPredecessorPks} — one entry per set head bit, in ascending bit-position order. It deliberately
 * carries NO chain state and NO run length: a head's state / run length can be flipped by a mutation in a DIFFERENT
 * leaf (whose own leaf stays byte-clean and is therefore not re-emitted, so any state stored here would go stale), and
 * both are recomputed at load — the run length from the distance to the next head mark across concatenated pages, the
 * state from the persisted head predecessor. The head predecessor IS dirty-safe: changing it always mutates the head's
 * own leaf.
 *
 * The {@link #headWords} bitset is exactly `ceil(recordIds.length / 64)` words wide (the meaningful prefix of the
 * live leaf's head mask); the producer is responsible for slicing the wider in-memory mask down to this length.
 *
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via
 * {@link AbstractLeafPagePart#computeUniquePartId}.
 * `streamId` is the {@link KeyCompressor} id of the sub-index's {@link LeafStreamKey} (one dictionary entry per
 * persisted sub-index, distinguished by a CHAIN-typed {@link AttributeKeyWithIndexType} so the CHAIN stream is disjoint
 * from the FILTER / SORT streams of the same attribute); `pageSequence` is the advance-only, never-reused page sequence
 * within that stream.
 *
 * Mirroring {@link SortIndexLeafPagePart}: a write-path page carries the sub-index `(entityIndexPrimaryKey,
 * attributeKey)` identity and resolves (and caches) `streamId` store-side in {@link #computeUniquePartIdAndSet} (the
 * engine that emits the page has no compressor); a read-path page (rehydrated by the serializer) carries the
 * already-known `streamId` and PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ChainIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = 3810572946183740265L;

	/**
	 * The leaf's record primary keys in tree (chain) order. Not sorted — the order is the value-tree ordering.
	 */
	@Nonnull @Getter private final int[] recordIds;
	/**
	 * The head bitset marking which of {@link #recordIds} are chain heads: bit `i` (LSB-first within word `i / 64`) set
	 * means `recordIds[i]` is a head. Exactly `ceil(recordIds.length / 64)` words wide.
	 */
	@Nonnull @Getter private final long[] headWords;
	/**
	 * The persisted head predecessor primary keys, one per set bit in {@link #headWords}, in ascending bit-position
	 * order (aligned with the set bits). A head's state / run length are NOT stored — they are recomputed at load.
	 */
	@Nonnull @Getter private final int[] headPredecessorPks;

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index (CHAIN-typed)
	 * @param pageSequence          the page sequence within the stream
	 * @param recordIds             the leaf's record primary keys in tree order
	 * @param headWords             the head bitset (`ceil(recordIds.length / 64)` words)
	 * @param headPredecessorPks    the head predecessor primary keys aligned with the set head bits
	 */
	public ChainIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence,
		@Nonnull int[] recordIds,
		@Nonnull long[] headWords,
		@Nonnull int[] headPredecessorPks
	) {
		super(entityIndexPrimaryKey, attributeKey, pageSequence);
		this.recordIds = recordIds;
		this.headWords = headWords;
		this.headPredecessorPks = headPredecessorPks;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId           the resolved stream id
	 * @param pageSequence       the page sequence within the stream
	 * @param recordIds          the leaf's record primary keys in tree order
	 * @param headWords          the head bitset (`ceil(recordIds.length / 64)` words)
	 * @param headPredecessorPks the head predecessor primary keys aligned with the set head bits
	 * @param storagePartPK      the precomputed primary key
	 */
	public ChainIndexLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull int[] recordIds,
		@Nonnull long[] headWords,
		@Nonnull int[] headPredecessorPks,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.recordIds = recordIds;
		this.headWords = headWords;
		this.headPredecessorPks = headPredecessorPks;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		return keyCompressor.getId(
			new LeafStreamKey(
				getEntityIndexPrimaryKeyOrThrowException(), getAttributeKeyOrThrowException()
			)
		);
	}
}
