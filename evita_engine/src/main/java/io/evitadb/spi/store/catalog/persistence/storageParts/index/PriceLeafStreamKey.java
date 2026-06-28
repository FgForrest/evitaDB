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

import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Identifies the per-super-price-index page STREAM under the granular price layout. A granular
 * {@link PriceListAndCurrencyPriceSuperIndex} persists its price-record tree leaves as individual
 * page storage parts keyed by `pack(streamId, pageSequence)` ({@link PriceListAndCurrencySuperIndexLeafPagePart});
 * `pageSequence` is unique only WITHIN a stream, so the stream itself needs a unique, compact, restart-stable `int` id —
 * and that id must encode the FULL sub-index identity in a single `int` because the other half of the joined `long` PK is
 * consumed by `pageSequence`.
 *
 * The id is obtained by registering this key with the catalog's {@link KeyCompressor}, exactly as the root part already
 * obtains its compressed id from {@link PriceIndexKey} ({@link PriceListAndCurrencyIndexStoragePart#computeUniquePartId}).
 * The difference is that the root part may spend both 32-bit halves of its PK on identity, so its compressed id need only
 * cover the {@link PriceIndexKey}; a leaf page may not, so its stream id must ALSO fold in the `entityIndexPrimaryKey`.
 * `PriceLeafStreamKey` is therefore `(entityIndexPrimaryKey, {@link PriceIndexKey})`. Unlike the FilterIndex's
 * `LeafStreamKey` there is NO stream-kind discriminator: a super price index owns exactly one page stream (its
 * price-record tree), so the identity alone is unique.
 *
 * The {@link KeyCompressor} is a bijective, restart-stable, transactionally-allocated dictionary persisted whole in the
 * catalog header, so `compressor.getId(priceLeafStreamKey)` is a GUARANTEED-unique (never probabilistic) and
 * deterministic `int`: distinct sub-indexes get distinct ids, and an assigned id never changes. The number of
 * sub-indexes is far below 2^31, so the id fits the high 32 bits of the page PK while `pageSequence` fills the low 32.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class PriceLeafStreamKey implements Comparable<PriceLeafStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = 7813049265018347561L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}. Folded into the stream identity because the same
	 * price list and currency combination (hence the same {@link PriceIndexKey}) exists independently in many entity
	 * indexes.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * The price list and currency identity of the sub-index, reusing the exact key the root part already compresses.
	 */
	@Nonnull @Getter private final PriceIndexKey priceIndexKey;

	/**
	 * Creates a price page-stream key from the owning entity index pk and the price list / currency identity.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param priceIndexKey         the price list and currency identity of the sub-index
	 */
	public PriceLeafStreamKey(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.priceIndexKey = priceIndexKey;
	}

	@Override
	public int compareTo(@Nonnull PriceLeafStreamKey o) {
		final int eipkResult = Integer.compare(this.entityIndexPrimaryKey, o.entityIndexPrimaryKey);
		return eipkResult != 0 ? eipkResult : this.priceIndexKey.compareTo(o.priceIndexKey);
	}

}
