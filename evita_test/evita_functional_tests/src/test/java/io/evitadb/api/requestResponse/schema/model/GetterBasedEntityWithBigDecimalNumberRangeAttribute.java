/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.dataType.BigDecimalNumberRange;

import javax.annotation.Nullable;

/**
 * Minimal entity model used to verify that {@code indexedDecimalPlaces} declared on a
 * {@link BigDecimalNumberRange} attribute is correctly propagated to the schema mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Entity(name = "BigDecimalRangeEntity")
public interface GetterBasedEntityWithBigDecimalNumberRangeAttribute {

	@PrimaryKey
	int getId();

	/**
	 * A range attribute over BigDecimal values. The {@code indexedDecimalPlaces = 2} instructs
	 * evitaDB to encode range bounds to comparable longs at two decimal places of precision.
	 */
	@Attribute(indexedDecimalPlaces = 2, filterable = true, nullable = true)
	@Nullable
	BigDecimalNumberRange getPriceRange();

}
