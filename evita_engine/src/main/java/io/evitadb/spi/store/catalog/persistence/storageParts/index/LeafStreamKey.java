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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Identifies the per-sub-index page STREAM under the granular FilterIndex layout. A granular
 * FilterIndex persists its bucket-tree leaves as individual page storage parts keyed by `join(streamId, pageSequence)`
 * ({@link FilterIndexLeafPagePart}); `pageSequence` is unique only WITHIN a stream, so the stream itself needs a unique,
 * compact, restart-stable `int` id — and that id must encode the FULL sub-index identity in a single `int` because the
 * other half of the joined `long` PK is consumed by `pageSequence`.
 *
 * The id is obtained by registering this key with the catalog's {@link KeyCompressor}, exactly as the root part already
 * obtains its compressed id from {@link AttributeKeyWithIndexType} ({@link AttributeIndexStoragePart#computeUniquePartId}).
 * The difference is that the root part may spend both 32-bit halves of its PK on identity, so its compressed id need
 * only cover `(attributeKey, indexType)`; a leaf page may not, so its stream id must ALSO fold in the
 * `entityIndexPrimaryKey`. `LeafStreamKey` is therefore `(entityIndexPrimaryKey, {@link AttributeKeyWithIndexType})`.
 *
 * The {@link KeyCompressor} is a bijective, restart-stable, transactionally-allocated dictionary persisted whole in the
 * catalog header, so `compressor.getId(leafStreamKey)` is a GUARANTEED-unique (never probabilistic) and deterministic
 * `int`: distinct sub-indexes get distinct ids, and an assigned id never changes. The number of sub-indexes is far below
 * 2^31, so the id fits the high 32 bits of the page PK while `pageSequence` fills the low 32.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class LeafStreamKey implements Comparable<LeafStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = 7234981250431765098L;

	/**
	 * Distinguishes the two independent page streams a single {@link io.evitadb.index.attribute.FilterIndex} persists
	 * under the granular layout: its value bucket tree ({@link #BUCKET}) and its optional threshold
	 * range tree ({@link #RANGE}). Both streams share the same `(entityIndexPrimaryKey, attributeKey)` identity, so the
	 * stream kind is what makes their {@link KeyCompressor} ids — and therefore their leaf-page primary keys
	 * `join(streamId, pageSequence)` — distinct; without it the two streams' page sequences would collide.
	 */
	public enum StreamKind {
		/**
		 * The {@code InvertedIndex} value bucket tree's page stream.
		 */
		BUCKET,
		/**
		 * The {@code RangeIndex} threshold tree's page stream.
		 */
		RANGE
	}

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}. Folded into the stream identity because the same
	 * attribute (hence the same {@link AttributeKeyWithIndexType}) exists independently in many entity indexes.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * The attribute + index-type identity of the sub-index, reusing the exact key the root part already compresses.
	 */
	@Nonnull @Getter private final AttributeKeyWithIndexType attributeKey;
	/**
	 * Which of the FilterIndex's two page streams this key identifies — see {@link StreamKind}.
	 */
	@Nonnull @Getter private final StreamKind streamKind;

	/**
	 * Creates a {@link StreamKind#BUCKET} stream key from the owning entity index pk and the already-built
	 * attribute/index-type identity.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 */
	public LeafStreamKey(int entityIndexPrimaryKey, @Nonnull AttributeKeyWithIndexType attributeKey) {
		this(entityIndexPrimaryKey, attributeKey, StreamKind.BUCKET);
	}

	/**
	 * Creates a stream key from the owning entity index pk, the already-built attribute/index-type identity and the
	 * stream kind.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param streamKind            which of the FilterIndex's two page streams this key identifies
	 */
	public LeafStreamKey(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		@Nonnull StreamKind streamKind
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeKey = attributeKey;
		this.streamKind = streamKind;
	}

	/**
	 * Creates a {@link StreamKind#BUCKET} stream key from the owning entity index pk, the attribute index key and the
	 * index type.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeIndexKey     the attribute key identifying the attribute and its locale
	 * @param indexType             the kind of attribute index (always {@link AttributeIndexType#FILTER} for now)
	 */
	public LeafStreamKey(
		int entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull AttributeIndexType indexType
	) {
		this(entityIndexPrimaryKey, new AttributeKeyWithIndexType(attributeIndexKey, indexType), StreamKind.BUCKET);
	}

	@Override
	public int compareTo(@Nonnull LeafStreamKey o) {
		final int eipkResult = Integer.compare(this.entityIndexPrimaryKey, o.entityIndexPrimaryKey);
		if (eipkResult != 0) {
			return eipkResult;
		}
		final int attributeResult = this.attributeKey.compareTo(o.attributeKey);
		return attributeResult != 0 ? attributeResult : this.streamKind.compareTo(o.streamKind);
	}

}
