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
 * Thrown when attempting to add a price that would create ambiguity in price selection due to overlapping
 * validity periods with an existing price that shares the same price list and currency.
 *
 * evitaDB requires that at any given point in time, there is at most one valid price for a given price list
 * and currency combination. When two prices with the same price list and currency have overlapping validity
 * periods, the system cannot deterministically choose which price to use as the selling price. This exception
 * is thrown during price mutations (both initial entity creation and updates) to prevent such ambiguous
 * configurations from being persisted.
 *
 * **When this is thrown:**
 * - During entity creation when multiple prices share price list + currency with overlapping validity
 * - During entity update when adding/modifying a price that would overlap with an existing price
 * - Thrown by `InitialPricesBuilder` and `ExistingPricesBuilder`
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
	 * The price already present in the entity that conflicts with the newly added price.
	 */
	@Nonnull @Getter private final PriceContract existingPrice;
	/**
	 * The price being added that would create the ambiguity.
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
