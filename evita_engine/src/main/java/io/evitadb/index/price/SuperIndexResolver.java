/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.price;

import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * Resolves the memory-expensive {@link PriceListAndCurrencyPriceSuperIndex} backing each reduced
 * {@link PriceListAndCurrencyPriceRefIndex} from a single {@link GlobalEntityIndex} — the GLOBAL entity index of the
 * owning collection and matching scope, whose {@link PriceSuperIndex} holds the shared super price indexes.
 *
 * Closing over the GLOBAL entity index — rather than over the owning entity collection — is what keeps this resolver
 * free of any collection or {@link io.evitadb.core.catalog.Catalog} back-reference: it pins nothing from the catalog
 * version that wired it. The lazy {@link PriceSuperIndex#getPriceIndex(PriceIndexKey)} read inside
 * {@link #apply(PriceIndexKey)} still consults the transactional combo map, so a price-list / currency combination added
 * later in the same transaction remains visible exactly as it did through the former owning-collection detour.
 *
 * The captured GLOBAL is deliberately kept inspectable through {@link #globalIndex()} — a record accessor, unlike an
 * opaque lambda. That accessor exists so a wiring pass can compare, by a single identity check, whether a given resolver
 * still points at a particular GLOBAL entity index, which is the enabling property for forwarding a reduced index across
 * catalog versions by reference (its GLOBAL is carried by reference on the clean-collection copy path) without silently
 * leaving a stale super-index pointer behind.
 *
 * @param globalIndex the GLOBAL entity index whose price super indexes back the reduced index being wired
 */
public record SuperIndexResolver(@Nonnull GlobalEntityIndex globalIndex)
	implements Function<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> {

	/**
	 * Resolves the super price index for a single price-list / currency combination from the captured GLOBAL entity
	 * index. Mirrors the non-null contract of the former owning-collection resolver: the combination is always present
	 * by the time a reduced index references it (a price is added to the super index before the reduced ref index that
	 * points at it), so a missing super index is a programming error rather than an expected absence.
	 *
	 * @param priceIndexKey the price-list / currency combination to resolve
	 * @return the super price index backing the combination (never `null`)
	 */
	@Nonnull
	@Override
	public PriceListAndCurrencyPriceSuperIndex apply(@Nonnull PriceIndexKey priceIndexKey) {
		final PriceListAndCurrencyPriceSuperIndex superIndex = this.globalIndex.getPriceIndex().getPriceIndex(priceIndexKey);
		Assert.isPremiseValid(
			superIndex != null,
			() -> "Super price index for `" + priceIndexKey + "` must exist in the GLOBAL entity index!"
		);
		return superIndex;
	}

}
