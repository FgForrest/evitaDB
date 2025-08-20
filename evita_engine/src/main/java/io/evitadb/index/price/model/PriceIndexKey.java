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

package io.evitadb.index.price.model;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.key.AbstractPriceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Currency;
import java.util.Objects;

/**
 * This key is used to distinguish different {@link PriceListAndCurrencyPriceSuperIndex} one from another.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceIndexKey extends AbstractPriceKey implements Comparable<PriceIndexKey> {
	public static final int MEMORY_SIZE = MemoryMeasuringConstants.OBJECT_HEADER_SIZE + 3 * MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.INT_SIZE;
	@Serial private static final long serialVersionUID = -8408134564383192647L;
	@Getter private final PriceInnerRecordHandling recordHandling;

	public PriceIndexKey(@Nonnull PriceKey priceKey, @Nonnull PriceInnerRecordHandling recordHandling) {
		super(priceKey.priceList(), priceKey.currency(), Objects.hash(priceKey.priceList(), priceKey.currency(), recordHandling));
		this.recordHandling = recordHandling;
	}

	public PriceIndexKey(@Nonnull String priceList, @Nonnull Currency currency, @Nonnull PriceInnerRecordHandling recordHandling) {
		super(priceList, currency, Objects.hash(priceList, currency, recordHandling));
		this.recordHandling = recordHandling;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		PriceIndexKey that = (PriceIndexKey) o;
		return this.hashCode == that.hashCode && this.priceList.equals(that.priceList) && this.currency.equals(that.currency) && this.recordHandling == that.recordHandling;
	}

	@Override
	public String toString() {
		return super.toString() + "/" + this.recordHandling;
	}

	@Override
	public int compareTo(PriceIndexKey o) {
		int result = this.currency.getCurrencyCode().compareTo(o.currency.getCurrencyCode());
		if (result == 0) {
			result = this.priceList.compareTo(o.priceList);
			if (result == 0) {
				result = this.recordHandling.compareTo(o.recordHandling);
			}
		}
		return result;
	}
}
