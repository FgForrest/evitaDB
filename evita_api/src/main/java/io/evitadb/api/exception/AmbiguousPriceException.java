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
 * This exception is thrown when user tries to add another price with same price list and currency with validity
 * overlaps the validity of already existing price with the same price list and currency. In this situation the Evita
 * wouldn't be able to decide which one of these prices should be used as selling price and that's why we report this
 * situation early with this exception.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AmbiguousPriceException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4405640415224088617L;
	@Nonnull @Getter private final PriceContract existingPrice;
	@Nonnull @Getter private final PriceContract ambiguousPrice;

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
