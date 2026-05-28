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

package io.evitadb.index.price.model.priceRecord;

import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Comparator;

/**
 * Compact, primitive-only representation of a single entity price used inside price indexes.
 *
 * Price indexes avoid holding full {@link PriceContract} objects in order to minimize heap usage.
 * This interface distills only the fields that are needed for price filtering, sorting, and translation
 * between price ids and entity primary keys.
 *
 * **Identity vs. content** — this is a critical distinction for the transactional change layer:
 *
 * - *Identity* is defined by {@link #internalPriceId()} alone. Both {@link #compareTo} and
 *   {@link #PRICE_RECORD_COMPARATOR} key on `internalPriceId`, and `equals`/`hashCode` in the
 *   concrete implementations ({@link PriceRecord}, {@link PriceRecordInnerRecordSpecific}) also key
 *   only on `internalPriceId`. Two records with the same `internalPriceId` are therefore considered
 *   the *same price slot* by sorted collections.
 * - *Content* covers all amount fields (`priceWithTax`, `priceWithoutTax`, `innerRecordId`, etc.).
 *   The {@link #differsFrom} default method provided by this interface compares full content, allowing
 *   the transactional index structures ({@link io.evitadb.index.array.ObjArrayChanges},
 *   {@link io.evitadb.index.set.SetChanges}) to detect a changed price amount and substitute the
 *   stale record with the updated one — even though identity-based lookup would consider them equal.
 *
 * Two specializations exist:
 *
 * - {@link PriceRecord} — represents a physical price for a single entity that has no inner record id.
 * - {@link CumulatedVirtualPriceRecord} — an on-the-fly aggregated record required by
 *   {@link PriceInnerRecordHandling#SUM the SUM price computation strategy}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceRecordContract extends Serializable, Comparable<PriceRecordContract>, ContentComparator<PriceRecordContract> {
	/**
	 * Canonical comparator for sorted collections of price records. Orders by {@link #internalPriceId()}
	 * only — two records representing the same price slot in the same entity are considered equal by
	 * this comparator regardless of their amount fields. Used by {@link io.evitadb.index.array.ObjArrayChanges}
	 * to locate a record's position in the sorted delegate array.
	 */
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

	/**
	 * Method allow to check if the price relates to the another price in terms of the inner record ID equality.
	 * Some price implementation might implement more complex logic to determine the relation.
	 *
	 * @param anotherPriceRecord another price to check relation with
	 * @return true if the price relates to the another price
	 */
	boolean relatesTo(@Nonnull PriceRecordContract anotherPriceRecord);

	/**
	 * Performs a deep, field-by-field content comparison that goes beyond the identity check of
	 * `equals` / {@link #PRICE_RECORD_COMPARATOR}.
	 *
	 * Two records with the same {@link #internalPriceId()} are *identity-equal* — they occupy the same
	 * slot in a price index — but may carry different amounts after a price update (e.g. a discount
	 * applied mid-transaction). This method returns `true` in that case, enabling the transactional
	 * change layer ({@link io.evitadb.index.array.ObjArrayChanges},
	 * {@link io.evitadb.index.set.SetChanges}) to substitute the stale record atomically instead of
	 * silently dropping the update.
	 *
	 * Fields compared: `internalPriceId`, `priceId`, `entityPrimaryKey`, `priceWithTax`,
	 * `priceWithoutTax`, and `innerRecordId`.
	 *
	 * @param otherObject the record to compare against; `null` is treated as "differs" (returns `true`)
	 * @return `true` if any content field differs from `otherObject`, `false` if all fields match
	 */
	@Override
	default boolean differsFrom(@Nullable PriceRecordContract otherObject) {
		if (otherObject == this) {
			return false;
		}
		if (otherObject == null) {
			return true;
		}
		return this.internalPriceId() != otherObject.internalPriceId()
			|| this.priceId() != otherObject.priceId()
			|| this.entityPrimaryKey() != otherObject.entityPrimaryKey()
			|| this.priceWithTax() != otherObject.priceWithTax()
			|| this.priceWithoutTax() != otherObject.priceWithoutTax()
			|| this.innerRecordId() != otherObject.innerRecordId();
	}

}
