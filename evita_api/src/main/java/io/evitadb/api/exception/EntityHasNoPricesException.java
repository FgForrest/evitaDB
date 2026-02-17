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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when price-related operations or query constraints target an entity collection that does not
 * support price functionality.
 *
 * Entity collections must explicitly enable price support via {@link EntitySchemaContract#isWithPrice()} in their
 * schema definition. This exception is thrown when:
 *
 * - **Query constraints** attempt to filter or sort by prices (e.g., `priceInPriceLists()`, `priceInCurrency()`,
 *   `priceBetween()`, `priceDiscount()`, `priceNatural()`)
 * - **Extra result computation** requests price histograms for entities without prices
 * - **Entity operations** attempt to access price data on entities whose schema disallows prices
 *
 * **When this exception occurs:**
 *
 * - Using `priceInPriceLists()` or similar constraints on non-priced entities
 * - Requesting `priceHistogram()` for entity types without price support
 * - Sorting by `priceNatural()` or `priceDiscount()` on non-priced entities
 * - Calling price accessor methods on entity types marked as non-priced
 *
 * **Resolution**: Enable price support in the entity schema by setting `withPrice()` during schema definition, or
 * remove price-related constraints from queries targeting non-priced entity collections.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityHasNoPricesException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7559807499840163851L;
	/**
	 * The entity type that does not support price functionality.
	 */
	@Getter private final String entityType;

	/**
	 * Creates exception identifying the entity type that lacks price support.
	 *
	 * @param entityType the name of the entity collection that does not allow prices
	 */
	public EntityHasNoPricesException(@Nonnull String entityType) {
		super(
			"Entity `" + entityType + "` targeted by query doesn't allow to keep any prices!"
		);
		this.entityType = entityType;
	}
}
