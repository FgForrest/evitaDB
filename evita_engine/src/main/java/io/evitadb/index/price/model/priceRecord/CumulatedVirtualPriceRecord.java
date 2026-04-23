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

package io.evitadb.index.price.model.priceRecord;

import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Comparator;

/**
 * Represents a virtual record, that is created as an "on the fly" record with computed prices.
 * This record is created for {@link PriceInnerRecordHandling#SUM sum price computation strategy}.
 * After price records are returned to the client they are garbage collected and stored nowhere
 * in the indexes.
 *
 * @param entityPrimaryKey the primary key of the entity this cumulated price belongs to
 * @param price            the cumulated price value (either with or without tax based on priceMode)
 * @param priceMode        determines whether {@link #price} represents a price with or without tax
 * @param innerRecordPrices map of inner record id to price records that were summed into this record
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record CumulatedVirtualPriceRecord(
	@Getter int entityPrimaryKey,
	@Getter int price,
	@Getter QueryPriceMode priceMode,
	@Getter @Nonnull IntObjectMap<PriceRecordContract> innerRecordPrices
) implements PriceRecordContract {

	@Serial private static final long serialVersionUID = -8702849059439375941L;

	/**
	 * Comparator that orders cumulated records by entity primary key,
	 * then price without tax, then price with tax.
	 */
	private static final Comparator<PriceRecordContract> FULL_COMPARATOR =
		Comparator.comparing(PriceRecordContract::entityPrimaryKey)
			.thenComparing(PriceRecordContract::priceWithoutTax)
			.thenComparing(PriceRecordContract::priceWithTax);

	@Override
	public int internalPriceId() {
		return 0;
	}

	@Override
	public int priceId() {
		return 0;
	}

	@Override
	public int priceWithTax() {
		return this.priceMode == QueryPriceMode.WITH_TAX ? this.price : 0;
	}

	@Override
	public int priceWithoutTax() {
		return this.priceMode == QueryPriceMode.WITHOUT_TAX ? this.price : 0;
	}

	@Override
	public int innerRecordId() {
		return 0;
	}

	@Override
	public boolean isInnerRecordSpecific() {
		return false;
	}

	@Override
	public boolean relatesTo(@Nonnull PriceRecordContract anotherPriceRecord) {
		return this.innerRecordPrices.containsKey(anotherPriceRecord.innerRecordId());
	}

	@Override
	public int compareTo(@Nonnull PriceRecordContract o) {
		return FULL_COMPARATOR.compare(this, o);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final CumulatedVirtualPriceRecord that = (CumulatedVirtualPriceRecord) o;
		return this.entityPrimaryKey == that.entityPrimaryKey
			&& this.price == that.price
			&& this.priceMode == that.priceMode;
	}

	@Override
	public int hashCode() {
		int result = Integer.hashCode(this.entityPrimaryKey);
		result = 31 * result + Integer.hashCode(this.price);
		result = 31 * result + this.priceMode.hashCode();
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "CumulatedVirtualPriceRecord{" +
			"entityPrimaryKey=" + this.entityPrimaryKey +
			", price=" + this.price +
			", priceMode=" + this.priceMode +
			", innerRecordPrices=[" + this.innerRecordPrices + "]" +
			'}';
	}
}
