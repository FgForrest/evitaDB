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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BigDecimalFieldHeaderDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of header arguments of fields representing concrete prices in {@link io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface PriceBigDecimalFieldHeaderDescriptor extends BigDecimalFieldHeaderDescriptor {

	PropertyDescriptor WITH_CURRENCY = PropertyDescriptor.builder()
		.name("withCurrency")
		.description("""
	        Parameter specifying if price value should be formatted and the formatted string should contain currency on output
			based on desired entity locale.
			""")
		.type(nullable(Boolean.class))
		.build();
}
