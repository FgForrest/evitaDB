/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.entity.model.entity.price;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * This DTO combines {@link PriceContract} and {@link PriceInternalIdContainer} together.
 *
 * @param delegate        price contract to delegate calls to
 * @param internalPriceId optional internal price id
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record PriceWithInternalIds(
	@Nonnull PriceContract delegate,
	@Nullable Integer internalPriceId
) implements PriceContract, PriceInternalIdContainer {
	@Serial private static final long serialVersionUID = 5008194525461751557L;

	@Override
	@Nullable
	public Integer getInternalPriceId() {
		return internalPriceId;
	}

	@Override
	public boolean dropped() {
		return delegate.dropped();
	}

	@Nonnull
	@Override
	public PriceKey priceKey() {
		return delegate.priceKey();
	}

	@Override
	public int priceId() {
		return delegate.priceId();
	}

	@Nonnull
	@Override
	public String priceList() {
		return delegate.priceList();
	}

	@Nonnull
	@Override
	public Currency currency() {
		return delegate.currency();
	}

	@Nullable
	@Override
	public Integer innerRecordId() {
		return delegate.innerRecordId();
	}

	@Nonnull
	@Override
	public BigDecimal priceWithoutTax() {
		return delegate.priceWithoutTax();
	}

	@Nonnull
	@Override
	public BigDecimal taxRate() {
		return delegate.taxRate();
	}

	@Nonnull
	@Override
	public BigDecimal priceWithTax() {
		return delegate.priceWithTax();
	}

	@Nullable
	@Override
	public DateTimeRange validity() {
		return delegate.validity();
	}

	@Override
	public boolean sellable() {
		return delegate.sellable();
	}

	@Override
	public int estimateSize() {
		return delegate.estimateSize() +
			MemoryMeasuringConstants.REFERENCE_SIZE +
			Optional.ofNullable(internalPriceId).stream().mapToInt(it -> MemoryMeasuringConstants.INT_SIZE).sum();
	}

	@Override
	public int version() {
		return delegate.version();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		PriceWithInternalIds that = (PriceWithInternalIds) o;
		return Objects.equals(internalPriceId, that.internalPriceId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(internalPriceId);
	}

	@Override
	public String toString() {
		return delegate + ", internal id " + internalPriceId;
	}
}
