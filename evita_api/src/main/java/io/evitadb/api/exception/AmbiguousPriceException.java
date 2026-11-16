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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when a change of an entity would leave it with a price whose validity period overlaps that of another price
 * sharing its price list, currency and inner record, which makes the price selection ambiguous.
 *
 * evitaDB requires that at any given point in time, there is at most one valid price for a given price list,
 * currency and inner record combination - for a given price list and currency when the entity has no inner record
 * handling. When two such prices have overlapping validity periods, the system cannot deterministically choose which
 * price to use as the selling price. This exception prevents such ambiguous configurations from being persisted.
 * Only a price the change touches - one it adds, revives, or moves to another validity or inner record, and every
 * price when it changes the inner record handling - is checked against the others, so an entity that already holds
 * an ambiguous pair stays updatable as long as the pair is left alone.
 *
 * **When this is thrown:**
 * - When an entity builder (`InitialPricesBuilder`, `ExistingPricesBuilder`) produces the mutations of the entity -
 *   by `toMutation()` or by the upsert of the builder - never when a price is set on it. The builder of an existing
 *   entity checks only when the entity was fetched with all its prices and otherwise leaves the check to the server.
 * - When the server stores an entity upsert. It judges each mutation against the state the previous mutation of the
 *   upsert left, so a price removed and set again unchanged within one upsert counts as revived, whereas a builder
 *   cancels such a removal out before it produces its mutations.
 *
 * **Resolution:**
 * - Adjust validity periods so they don't overlap
 * - Remove one of the conflicting prices
 * - Use different price lists or currencies to distinguish the prices
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AmbiguousPriceException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4405640415224088617L;
	/**
	 * The price {@link #ambiguousPrice} competes with - a price the entity already holds, or another price the same
	 * change touches.
	 */
	@Nonnull @Getter private final PriceContract existingPrice;
	/**
	 * The price the change touches that would create the ambiguity.
	 */
	@Nonnull @Getter private final PriceContract ambiguousPrice;

	/**
	 * Creates exception detailing which two prices have conflicting validity periods.
	 */
	public AmbiguousPriceException(@Nonnull PriceContract existingPrice, @Nonnull PriceContract ambiguousPrice) {
		super(
			"Price `" + ambiguousPrice.priceKey() + "` with id `" + ambiguousPrice.priceId() + "` cannot be added to the entity. " +
				"There is already present price `" + existingPrice.priceKey() + "` with id `" + existingPrice.priceId() + "` " +
				"that would create conflict with newly added price because their validity spans overlap."
		);
		this.existingPrice = existingPrice;
		this.ambiguousPrice = ambiguousPrice;
	}

}
