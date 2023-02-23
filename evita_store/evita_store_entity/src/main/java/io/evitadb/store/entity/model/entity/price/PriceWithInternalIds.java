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
import lombok.Getter;

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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceWithInternalIds implements PriceContract, PriceInternalIdContainer {
	@Serial private static final long serialVersionUID = 5008194525461751557L;
	private final PriceContract delegate;
	@Getter private final Integer internalPriceId;

	public PriceWithInternalIds(@Nonnull PriceContract delegate, @Nullable Integer internalPriceId) {
		this.delegate = delegate;
		this.internalPriceId = internalPriceId;
	}

	@Override
	public boolean isDropped() {
		return delegate.isDropped();
	}

	@Nonnull
	@Override
	public PriceKey getPriceKey() {
		return delegate.getPriceKey();
	}

	@Override
	public int getPriceId() {
		return delegate.getPriceId();
	}

	@Nonnull
	@Override
	public String getPriceList() {
		return delegate.getPriceList();
	}

	@Nonnull
	@Override
	public Currency getCurrency() {
		return delegate.getCurrency();
	}

	@Nullable
	@Override
	public Integer getInnerRecordId() {
		return delegate.getInnerRecordId();
	}

	@Nonnull
	@Override
	public BigDecimal getPriceWithoutTax() {
		return delegate.getPriceWithoutTax();
	}

	@Nonnull
	@Override
	public BigDecimal getTaxRate() {
		return delegate.getTaxRate();
	}

	@Nonnull
	@Override
	public BigDecimal getPriceWithTax() {
		return delegate.getPriceWithTax();
	}

	@Nullable
	@Override
	public DateTimeRange getValidity() {
		return delegate.getValidity();
	}

	@Override
	public boolean isSellable() {
		return delegate.isSellable();
	}

	@Override
	public int getVersion() {
		return delegate.getVersion();
	}

	@Override
	public int estimateSize() {
		return delegate.estimateSize() +
			MemoryMeasuringConstants.REFERENCE_SIZE +
			Optional.ofNullable(internalPriceId).stream().mapToInt(it -> MemoryMeasuringConstants.INT_SIZE).sum();
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
