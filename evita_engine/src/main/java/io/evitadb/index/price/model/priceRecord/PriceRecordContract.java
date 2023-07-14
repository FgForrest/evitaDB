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

package io.evitadb.index.price.model.priceRecord;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Price record envelopes single price of the entity. This data structure allows translating price ids and inner record
 * ids to entity primary key. Also price amounts are used for sorting by price. Price indexes don't use original
 * {@link PriceContract} to minimize memory consumption (this class contains only primitive types).
 *
 * There two specializations of this interface:
 *
 * - {@link PriceRecord} - represents a physical price recorded for single entity and present in indexes
 * - {@link CumulatedVirtualPriceRecord} - represents an "on the fly" record with computed prices that are required by
 * {@link PriceInnerRecordHandling#SUM sum price computation strategy}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceRecordContract extends Serializable, Comparable<PriceRecordContract> {
	Comparator<PriceRecordContract> PRICE_RECORD_COMPARATOR = Comparator.comparing(PriceRecordContract::internalPriceId);

	/**
	 * Returns internal id for {@link PriceContract#priceId()}. The is unique for the price identified
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
	 * The inner record id (int) is encoded with entityPrimaryKey into the local innerRecordId (long).
	 * This allows us to sort correctly by entity primary key first and be able to any time extract both entity primary
	 * key or inner record id from it.
	 */
	boolean isInnerRecordSpecific();

}
