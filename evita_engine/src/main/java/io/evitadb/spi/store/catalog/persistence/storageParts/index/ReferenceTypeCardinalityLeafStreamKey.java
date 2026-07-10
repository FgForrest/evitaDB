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
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Identifies the per-{@link io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex} page STREAM under the granular
 * storage layout. A granular reference-type cardinality index persists its cardinality bucket-tree leaves as individual
 * page storage parts keyed by `pack(streamId, pageSequence)` ({@link ReferenceTypeCardinalityIndexLeafPagePart});
 * `pageSequence` is unique only WITHIN a stream, so the stream itself needs a unique, compact, restart-stable `int` id —
 * and that id must encode the FULL sub-index identity in a single `int` because the other half of the joined `long` PK is
 * consumed by `pageSequence`.
 *
 * The id is obtained by registering this key with the catalog's {@link KeyCompressor}, exactly as the root part already
 * obtains its compressed id from `(entityIndexPrimaryKey, referenceName)`
 * ({@link ReferenceTypeCardinalityIndexStoragePart#computeUniquePartId}). The difference is that the root part may spend
 * both 32-bit halves of its PK on identity (`pack(entityIndexPrimaryKey, compressor.getId(ReferenceNameKey))`), so its
 * compressed id need only cover the reference name; a leaf page may not, so its stream id must ALSO fold in the
 * `entityIndexPrimaryKey`. `ReferenceTypeCardinalityLeafStreamKey` is therefore `(entityIndexPrimaryKey, referenceName)`
 * — the entity-index-level analog of {@link GlobalUniqueLeafStreamKey} (whose identity is the catalog-level
 * `(scope, attributeKey)` pair).
 *
 * The {@link KeyCompressor} is a bijective, restart-stable, transactionally-allocated dictionary persisted whole in the
 * catalog header, so `compressor.getId(referenceTypeCardinalityLeafStreamKey)` is a GUARANTEED-unique (never
 * probabilistic) and deterministic `int`: distinct sub-indexes get distinct ids, and an assigned id never changes. The
 * number of sub-indexes is far below 2^31, so the id fits the high 32 bits of the page PK while `pageSequence` fills the
 * low 32.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class ReferenceTypeCardinalityLeafStreamKey implements Comparable<ReferenceTypeCardinalityLeafStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = 5839204176450283917L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}. Folded into the stream identity because the same
	 * reference name exists independently in every entity index.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference this sub-index is related to, reusing the exact discriminator the root part already
	 * compresses.
	 */
	@Nonnull @Getter private final String referenceName;

	/**
	 * Creates a reference-type-cardinality page-stream key from the owning entity-index primary key and the reference
	 * name.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning {@link io.evitadb.index.EntityIndex}
	 * @param referenceName         name of the reference the sub-index is related to
	 */
	public ReferenceTypeCardinalityLeafStreamKey(int entityIndexPrimaryKey, @Nonnull String referenceName) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
	}

	@Override
	public int compareTo(@Nonnull ReferenceTypeCardinalityLeafStreamKey o) {
		final int pkResult = Integer.compare(this.entityIndexPrimaryKey, o.entityIndexPrimaryKey);
		return pkResult != 0 ? pkResult : this.referenceName.compareTo(o.referenceName);
	}

}
