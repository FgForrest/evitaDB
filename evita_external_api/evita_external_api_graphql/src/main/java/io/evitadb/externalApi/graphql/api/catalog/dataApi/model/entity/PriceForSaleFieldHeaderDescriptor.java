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

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.time.OffsetDateTime;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of header parameters of {@link EntityDescriptor#PRICE_FOR_SALE} field.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface PriceForSaleFieldHeaderDescriptor {

	PropertyDescriptor PRICE_LIST = PropertyDescriptor.builder()
		.name("priceList")
		.description("""
	         Parameter specifying desired price list of output price.
	         Whenever possible, use constraint `priceInPriceLists` in main query instead.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor PRICE_LISTS = PropertyDescriptor.builder()
		.name("priceList")
		.description("""
	         Parameter specifying list of price lists ordered by priority for defining output price.
	         Whenever possible, use constraint `priceInPriceLists` in main query instead.
			""")
		.type(nullable(String[].class))
		.build();
	PropertyDescriptor CURRENCY = PropertyDescriptor.builder()
		.name("currency")
		.description("""
	         Parameter specifying desired currency of output price if different currency that already defined is desired.
	         Whenever possible, use constraint `priceInCurrency` in main query instead.
			""")
		// type is expected to be a currency enum
		.build();
	PropertyDescriptor VALID_IN = PropertyDescriptor.builder()
		.name("validIn")
		.description("""
			Parameter specifying when output price should be valid. If both `validInNow` and `validIn` parameters are
			specified `validIn` is used.
			Whenever possible, use constraint `priceValidIn` in main query instead.
			""")
		.type(nullable(OffsetDateTime.class))
		.build();
	PropertyDescriptor VALID_NOW = PropertyDescriptor.builder()
		.name("validNow")
		.description("""
			Parameter specifying when output price should be valid. The date time is resolved to `now` by evitaDB. If both `validNow`
			and `validIn` parameters are specified `validIn` is used.
			Whenever possible, use constraint `priceValidInNow` in main query instead.
			""")
		.type(nullable(Boolean.class))
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
