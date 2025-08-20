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

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Price record envelopes single price of the entity. This data structure allows translating price ids and inner record
 * ids to entity primary key. Also price amounts are used for sorting by price. Price indexes don't use original
 * {@link PriceContract} to minimize memory consumption (this class contains only primitive types).
 *
 * @param internalPriceId  Contains internal id for {@link PriceContract#priceId()}. The is unique for the price identified
 *                         by {@link PriceKey} inside single entity. The id is different for two prices sharing same {@link PriceKey}
 *                         but are present in different entities.
 * @param priceId          Refers to {@link PriceContract#priceId()}. The price is unique only within the scope of the primary key.
 * @param entityPrimaryKey Refers to {@link Entity#getPrimaryKey()}.
 * @param innerRecordId    Refers to {@link PriceContract#innerRecordId()}.
 *                         Contains zero if original inner record id is NULL. The innerRecordId is unique only within the scope
 *                         of the primary key.
 * @param priceWithTax     Refers to {@link PriceContract#priceWithTax()}.
 * @param priceWithoutTax  Refers to {@link PriceContract#priceWithoutTax()}.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record PriceRecordInnerRecordSpecific(
	@Getter int internalPriceId,
	@Getter int priceId,
	@Getter int entityPrimaryKey,
	@Getter int innerRecordId,
	@Getter int priceWithTax,
	@Getter int priceWithoutTax
) implements PriceRecordContract {
	@Serial private static final long serialVersionUID = 3145108662152707264L;

	@Override
	public boolean isInnerRecordSpecific() {
		return true;
	}

	@Override
	public boolean relatesTo(@Nonnull PriceRecordContract anotherPriceRecord) {
		return this.innerRecordId == anotherPriceRecord.innerRecordId();
	}

	@Override
	public int compareTo(@Nonnull PriceRecordContract other) {
		return PRICE_RECORD_COMPARATOR.compare(this, other);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final PriceRecordInnerRecordSpecific that = (PriceRecordInnerRecordSpecific) o;
		return this.internalPriceId == that.internalPriceId;
	}

	@Override
	public int hashCode() {
		return Integer.hashCode(this.internalPriceId);
	}
}
