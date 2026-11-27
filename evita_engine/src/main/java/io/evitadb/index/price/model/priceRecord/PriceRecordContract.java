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
 * Compact, primitive-only representation of a single entity price used inside price indexes.
 *
 * Price indexes avoid holding full {@link PriceContract} objects in order to minimize heap usage.
 * This interface distills only the fields that are needed for price filtering, sorting, and translation
 * between price ids and entity primary keys.
 *
 * {@link #PRICE_RECORD_COMPARATOR} orders indexed records by {@link #internalPriceId()}.
 * The physical record implementations, {@link PriceRecord} and {@link PriceRecordInnerRecordSpecific},
 * use this ID for their natural order, equality within each implementation, and hash code.
 * Indexed records with the same ID occupy the same slot in the price index tree
 * ({@link io.evitadb.index.bPlusTree.TransactionalElementBPlusTree}).
 *
 * Two specializations exist:
 *
 * - {@link PriceRecord} — represents a physical price for a single entity that has no inner record id.
 * - {@link CumulatedVirtualPriceRecord} — an on-the-fly aggregated record required by
 * {@link PriceInnerRecordHandling#SUM the SUM price computation strategy}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceRecordContract extends Serializable, Comparable<PriceRecordContract> {

	/**
	 * Canonical comparator for sorted collections of price records. Orders by {@link #internalPriceId()}
	 * only — two records representing the same price slot in the same entity are considered equal by
	 * this comparator regardless of their amount fields.
	 */
	@Nonnull
	Comparator<PriceRecordContract> PRICE_RECORD_COMPARATOR =
		Comparator.comparingInt(PriceRecordContract::internalPriceId);

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
