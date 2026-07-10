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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Identifies the per-global-unique-index page STREAM under the granular {@link CatalogIndex} layout. A granular
 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} persists its value-to-entity-tuple tree leaves as individual page
 * storage parts keyed by `pack(streamId, pageSequence)` ({@link GlobalUniqueIndexLeafPagePart}); `pageSequence` is unique
 * only WITHIN a stream, so the stream itself needs a unique, compact, restart-stable `int` id — and that id must encode
 * the FULL sub-index identity in a single `int` because the other half of the joined `long` PK is consumed by
 * `pageSequence`.
 *
 * The id is obtained by registering this key with the catalog's {@link KeyCompressor}, exactly as the root part already
 * obtains its compressed id from `(scope, attributeKey)`
 * ({@link GlobalUniqueIndexStoragePart#computeUniquePartId}). The difference is that the root part may spend both 32-bit
 * halves of its PK on identity (`pack(scope.ordinal(), compressor.getId(attributeKey))`), so its compressed id need only
 * cover the {@link AttributeKey}; a leaf page may not, so its stream id must ALSO fold in the {@link Scope}.
 * `GlobalUniqueLeafStreamKey` is therefore `(scope, attributeKey)`. Unlike the FilterIndex's `LeafStreamKey` there is NO
 * `entityIndexPrimaryKey` and NO stream-kind discriminator: a global unique index is catalog-level and owns exactly one
 * page stream (its value tree), so the `(scope, attributeKey)` identity alone is unique.
 *
 * The {@link KeyCompressor} is a bijective, restart-stable, transactionally-allocated dictionary persisted whole in the
 * catalog header, so `compressor.getId(globalUniqueLeafStreamKey)` is a GUARANTEED-unique (never probabilistic) and
 * deterministic `int`: distinct sub-indexes get distinct ids, and an assigned id never changes. The number of
 * sub-indexes is far below 2^31, so the id fits the high 32 bits of the page PK while `pageSequence` fills the low 32.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class GlobalUniqueLeafStreamKey implements Comparable<GlobalUniqueLeafStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = -6749203518744620731L;

	/**
	 * Scope of the owning {@link CatalogIndex}. Folded into the stream identity because the same attribute (hence the
	 * same {@link AttributeKey}) exists independently in each scope.
	 */
	@Nonnull @Getter private final Scope scope;
	/**
	 * The attribute identity of the sub-index, reusing the exact key the root part already compresses.
	 */
	@Nonnull @Getter private final AttributeKey attributeKey;

	/**
	 * Creates a global-unique page-stream key from the owning catalog-index scope and the indexed attribute identity.
	 *
	 * @param scope        scope of the owning {@link CatalogIndex}
	 * @param attributeKey the attribute identity of the sub-index
	 */
	public GlobalUniqueLeafStreamKey(@Nonnull Scope scope, @Nonnull AttributeKey attributeKey) {
		this.scope = scope;
		this.attributeKey = attributeKey;
	}

	@Override
	public int compareTo(@Nonnull GlobalUniqueLeafStreamKey o) {
		final int scopeResult = this.scope.compareTo(o.scope);
		return scopeResult != 0 ? scopeResult : this.attributeKey.compareTo(o.attributeKey);
	}

}
