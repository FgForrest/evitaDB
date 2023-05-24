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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.time.OffsetDateTime;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor for header arguments of {@link CatalogDataApiRootDescriptor#GET_ENTITY}
 * query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface GetEntityQueryHeaderDescriptor {

	PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
		.name("primaryKey")
		.description("""
			Parameter specifying desired primary key of queried entity
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor LOCALE = PropertyDescriptor.builder()
		.name("locale")
		.description("""
			Parameter specifying desired locale of queried entity and its inner datasets
			""")
		// type is expected to be a locale enum
		.build();
	PropertyDescriptor PRICE_IN_CURRENCY = PropertyDescriptor.builder()
		.name("priceInCurrency")
		.description("""
			Parameter specifying desired currency of price for sale of queried entity
			""")
		// type is expected to be a currency enum
		.build();
	PropertyDescriptor PRICE_IN_PRICE_LISTS = PropertyDescriptor.builder()
		.name("priceInPriceLists")
		.description("""
			Parameter specifying desired price lists of price for sale of queried entity
			""")
		.type(nullable(String[].class))
		.build();
	PropertyDescriptor PRICE_VALID_IN = PropertyDescriptor.builder()
		.name("priceValidIn")
		.description("""
			Parameter specifying desired validity of price for sale of queried entity. The date time is resolved to
			`now` by evitaDB. If both `priceValidInNow` and `priceValidIn` parameters are specified `priceValidIn` is used.
			""")
		.type(nullable(OffsetDateTime.class))
		.build();
	PropertyDescriptor PRICE_VALID_NOW = PropertyDescriptor.builder()
		.name("priceValidNow")
		.description("""
			Parameter specifying desired validity of price for sale of queried entity. The date time is resolved to
			`now` by evitaDB. If both `priceValidNow` and `priceValidIn` parameters are specified `priceValidIn` is used.
			""")
		.type(nullable(Boolean.class))
		.build();
}
