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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of header parameters of {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor#PRICE} field.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
// TOBEDONE #538: deprecated, remove
@Deprecated(since = "2024.3", forRemoval = true)
public interface PriceFieldHeaderDescriptor {

	PropertyDescriptor PRICE_LIST = PropertyDescriptor.builder()
		.name("priceList")
		.description("""
			Parameter specifying desired price list of output price.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor CURRENCY = PropertyDescriptor.builder()
		.name("currency")
		.description("""
			Parameter specifying desired currency of output price if different currency that already defined is desired.
			""")
		// type is expected to be a currency enum
		.build();
	PropertyDescriptor LOCALE = PropertyDescriptor.builder()
		.name("locale")
		.description("""
			Parameter specifying desired locale price formatting.
			If not specified, desired entity locale is used instead.
			""")
		// type is expected to be a locale enum
		.build();
}
