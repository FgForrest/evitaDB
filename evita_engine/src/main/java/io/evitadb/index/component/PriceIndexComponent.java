/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.price.VoidPriceIndex;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Adapter that wraps any flavour of {@link PriceIndexContract} (super, ref, or
 * {@link VoidPriceIndex}) as an {@link IndexComponent}. An adapter is used rather than
 * implementing the interface on `AbstractPriceIndex` directly because the three flavours
 * live on different concrete types and one wrapper keeps the parent index code uniform.
 *
 * For the void flavour every operation is a no-op — the void instance carries no state,
 * has no `getModifiedStorageParts` / `resetDirty` / `removeLayer` method, and the
 * `getPriceListAndCurrencyIndexes()` accessor returns the empty collection, so the
 * manifest contribution is trivially empty as well.
 */
public final class PriceIndexComponent implements IndexComponent {

	/**
	 * The wrapped price index. May be a {@link PriceSuperIndex}, a {@link PriceRefIndex},
	 * or {@link VoidPriceIndex#INSTANCE} on {@link io.evitadb.index.ReferencedTypeEntityIndex}.
	 */
	@Nonnull private final PriceIndexContract priceIndex;

	/**
	 * @param priceIndex the wrapped price index
	 */
	public PriceIndexComponent(@Nonnull PriceIndexContract priceIndex) {
		this.priceIndex = priceIndex;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// emit modified storage parts — only the non-void flavours support this; the void
		// flavour has no live data to advertise so we skip the call entirely
		if (this.priceIndex instanceof PriceSuperIndex superIndex) {
			superIndex.getModifiedStorageParts(entityIndexPrimaryKey, trappedChanges);
		} else if (this.priceIndex instanceof PriceRefIndex refIndex) {
			refIndex.getModifiedStorageParts(entityIndexPrimaryKey, trappedChanges);
		} else if (this.priceIndex instanceof VoidPriceIndex) {
			// intentional no-op: VoidPriceIndex carries no state and has no modified parts to emit
		} else {
			throw new GenericEvitaInternalError(
				"Unexpected PriceIndexContract impl: " + this.priceIndex.getClass()
			);
		}
		// announce every live price-list-and-currency key into the manifest; for the void
		// flavour `getPriceListAndCurrencyIndexes()` returns the empty collection so this
		// loop is a no-op without an explicit branch
		final Collection<? extends PriceListAndCurrencyPriceIndex> indexes =
			this.priceIndex.getPriceListAndCurrencyIndexes();
		for (final PriceListAndCurrencyPriceIndex pli : indexes) {
			manifest.addPriceKey(pli.getPriceIndexKey());
		}
	}

	@Override
	public void resetDirty() {
		// only the non-void flavours implement IndexDataStructure.resetDirty
		if (this.priceIndex instanceof IndexDataStructure ids) {
			ids.resetDirty();
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// only the non-void flavours implement TransactionalLayerProducer.removeLayer
		if (this.priceIndex instanceof TransactionalLayerProducer<?, ?> producer) {
			producer.removeLayer(transactionalLayer);
		}
	}

}
