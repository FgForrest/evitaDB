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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Comparator;

/**
 * Price record envelopes single price of the entity. This data structure allows translating price ids and inner record
 * ids to entity primary key. Also price amounts are used for sorting by price. Price indexes don't use original
 * {@link PriceContract} to minimize memory consumption (this class contains only primitive types).
 *
 * There are two specializations of this interface:
 *
 * - {@link PriceRecord} - represents a physical price recorded for single entity and present in indexes
 * - {@link CumulatedVirtualPriceRecord} - represents an "on the fly" record with computed prices that are required by
 * {@link PriceInnerRecordHandling#SUM sum price computation strategy}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceRecordContract extends Serializable, Comparable<PriceRecordContract> {

	/**
	 * Comparator that orders {@link PriceRecordContract} instances
	 * by {@link #internalPriceId()} in ascending order.
	 */
	@Nonnull
	Comparator<PriceRecordContract> PRICE_RECORD_COMPARATOR =
		Comparator.comparing(PriceRecordContract::internalPriceId);

	/**
	 * Returns internal id for {@link PriceContract#priceId()}. It is unique for the price identified
	 * by {@link PriceKey} inside single entity. The id is different for two prices sharing same {@link PriceKey}
	 * but are present in different entities.
	 */
	int internalPriceId();

	/**
	 * Refers to original {@link PriceContract#priceId()}.
	 */
	int priceId();

	/**
	 * Refers to {@link Entity#getPrimaryKey()}.
	 */
	int entityPrimaryKey();

	/**
	 * Refers to {@link PriceContract#priceWithTax()}.
	 */
	int priceWithTax();

	/**
	 * Refers to {@link PriceContract#priceWithoutTax()}.
	 */
	int priceWithoutTax();

	/**
	 * Refers to original {@link PriceContract#innerRecordId()}. Returns zero if original inner record id is NULL.
	 */
	int innerRecordId();

	/**
	 * Returns true if price record has inner record id specified (non-null).
	 * The inner record id is stored alongside the entity primary key, which
	 * allows us to be able to extract at any time both entity primary key
	 * and inner record id from the record.
	 */
	boolean isInnerRecordSpecific();

	/**
	 * Method allows checking if the price relates to another price in terms of the inner record ID equality.
	 * Some price implementation might implement more complex logic to determine the relation.
	 *
	 * @param anotherPriceRecord another price to check relation with
	 * @return true if the price relates to another price
	 */
	boolean relatesTo(@Nonnull PriceRecordContract anotherPriceRecord);

}
