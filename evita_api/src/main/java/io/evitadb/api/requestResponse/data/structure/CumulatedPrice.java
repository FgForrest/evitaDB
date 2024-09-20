/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Special form of the {@link PriceContract} that represents virtual price representing a sum of multiple real prices.
 * It's kind of aggregation price that is used to represent a price of a complex product that is composed of multiple
 * sub-products. Entity keys of the sub-products are stored in the innerRecordIds field.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see PriceKey for details
 */
public record CumulatedPrice(
	int version,
	@Nonnull PriceKey priceKey,
	@Nullable Set<Integer> innerRecordIds,
	@Nonnull BigDecimal priceWithoutTax,
	@Nonnull BigDecimal taxRate,
	@Nonnull BigDecimal priceWithTax
) implements PriceContract {

	@Override
	public int priceId() {
		return priceKey.priceId();
	}

	@Nonnull
	@Override
	public String priceList() {
		return priceKey.priceList();
	}

	@Nonnull
	@Override
	public Currency currency() {
		return priceKey.currency();
	}

	@Nullable
	@Override
	public Integer innerRecordId() {
		return null;
	}

	@Nullable
	@Override
	public DateTimeRange validity() {
		return null;
	}

	@Override
	public boolean indexed() {
		return false;
	}

	@Override
	public boolean dropped() {
		return false;
	}

	@Override
	public boolean relatesTo(@Nonnull PriceContract anotherPrice) {
		return this.innerRecordIds.contains(anotherPrice.innerRecordId());
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// version
			MemoryMeasuringConstants.INT_SIZE +
			// dropped
			MemoryMeasuringConstants.BYTE_SIZE +
			// sellable
			MemoryMeasuringConstants.BYTE_SIZE +
			// key
			MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// price id
			MemoryMeasuringConstants.INT_SIZE +
			// price list
			EvitaDataTypes.estimateSize(priceKey.priceList()) +
			// currency
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// inner record id
			MemoryMeasuringConstants.REFERENCE_SIZE + innerRecordIds.size() * MemoryMeasuringConstants.REFERENCE_SIZE +
			// price without and with tax + tax
			3 * (MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.BIG_DECIMAL_SIZE);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		CumulatedPrice price = (CumulatedPrice) o;

		if (version != price.version) return false;
		return priceKey.equals(price.priceKey);
	}

	@Override
	public int hashCode() {
		int result = version;
		result = 31 * result + priceKey.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return "\uD83D\uDCB0 \uD83D\uDCB5 " + priceWithTax + " " + priceKey.currency() + " (" + taxRate + "%)" +
			", price list " + priceKey.priceList() +
			", external id " + priceKey.priceId() +
			", SUM of inner record ids: [" + innerRecordIds.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]";
	}

}
