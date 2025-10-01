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

import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.math.BigDecimal;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link UpsertPriceMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface UpsertPriceMutationDescriptor extends PriceMutationDescriptor {

	PropertyDescriptor INNER_RECORD_ID = PropertyDescriptor.builder()
		.name("innerRecordId")
		.description("""
			Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
			so that the aggregating product can represent them in certain views on the product. In that case there is need
			to distinguish the projected prices of the subordinate product in the one that represents them.
						
			Inner record id must contain positive value.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor PRICE_WITHOUT_TAX = PropertyDescriptor.builder()
		.name("priceWithoutTax")
		.description("""
			Price without tax.
			""")
		.type(nonNull(BigDecimal.class))
		.build();
	PropertyDescriptor TAX_RATE = PropertyDescriptor.builder()
		.name("taxRate")
		.description("""
			Tax rate percentage (i.e. for 19% it'll be 19.00)
			""")
		.type(nonNull(BigDecimal.class))
		.build();
	PropertyDescriptor PRICE_WITH_TAX = PropertyDescriptor.builder()
		.name("priceWithTax")
		.description("""
			Price with tax.
			""")
		.type(nonNull(BigDecimal.class))
		.build();
	PropertyDescriptor VALIDITY = PropertyDescriptor.builder()
		.name("validity")
		.description("""
			Date and time interval for which the price is valid (inclusive).
			""")
		.type(nullable(DateTimeRange.class))
		.build();
	PropertyDescriptor INDEXED = PropertyDescriptor.builder()
		.name("indexed")
		.description("""
			Controls whether price is subject to filtering / sorting logic, non-indexed prices will be fetched along with
			entity but won't be considered when evaluating search query. These prices may be
			used for "informational" prices such as reference price (the crossed out price often found on e-commerce sites
			as "usual price") but are not considered as the "selling" price.
			""")
		.type(nonNull(Boolean.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("UpsertPriceMutation")
		.description("""
			This mutation allows to create / update `price` of the entity.
			""")
		.staticFields(List.of(
			MUTATION_TYPE,
			PRICE_ID,
			PRICE_LIST,
			CURRENCY,
			INNER_RECORD_ID,
			PRICE_WITHOUT_TAX,
			TAX_RATE,
			PRICE_WITH_TAX,
			VALIDITY,
			INDEXED
		))
		.build();
}
