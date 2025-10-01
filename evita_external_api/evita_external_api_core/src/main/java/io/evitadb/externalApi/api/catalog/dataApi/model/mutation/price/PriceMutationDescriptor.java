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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import java.util.Currency;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Abstract descriptor for all {@link PriceMutation}s.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface PriceMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor PRICE_ID = PropertyDescriptor.builder()
		.name("priceId")
		.description("""
			Contains identification of the price in the external systems. This id is expected to be used for the synchronization
			of the price in relation with the primary source of the prices.
						
			This id is used to uniquely find a price within same price list and currency and is mandatory.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor PRICE_LIST = PropertyDescriptor.builder()
		.name("priceList")
		.description("""
			Contains identification of the price list in the external system. Each price must reference a price list. Price list
			identification may refer to another Evita entity or may contain any external price list identification
			(for example id or unique name of the price list in the external system).
						
			Single entity is expected to have single price for the price list unless there is `validity` specified.
			In other words there is no sense to have multiple concurrently valid prices for the same entity that have roots
			in the same price list.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor CURRENCY = PropertyDescriptor.builder()
		.name("currency")
		.description("""
			Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
			""")
		.type(nonNull(Currency.class))
		.build();
}
