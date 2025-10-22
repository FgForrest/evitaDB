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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity;

import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetEntitySchemaWithPriceMutationDescriptor extends EntitySchemaMutationDescriptor {

	PropertyDescriptor WITH_PRICE = PropertyDescriptor.builder()
		.name("withPrice")
		.description("""
			Whether entities of this type holds price information.
			
			Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce
			systems and highly affects performance of the entities filtering and sorting, they deserve first class support
			in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
			customers.
			
			Specifying prices on entity allows usage of `priceValidIn`, `priceInCurrency`
			`priceBetween`, and `priceInPriceLists` filtering constraints and also price
			ordering of the entities. Additional requirements
			`priceHistogram` and `priceType` can be used in query as well.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor INDEXED_IN_SCOPES = PropertyDescriptor.builder()
		.name("indexedInScopes")
		.description("""
			Specifies set of all scopes the price information is indexed in and can be used for filtering entities and computation
			of extra data. If the price information is not indexed, it is still available on the entity itself (i.e. entity
			can define its price), but it is not possible to work with the price information in any other way (calculating
			price histogram, filtering, sorting by price, etc.).
			
			Prices can be also set as non-indexed individually by setting indexed property on price to false.
			""")
		.type(nullable(Scope[].class))
		.build();
	PropertyDescriptor INDEXED_PRICE_PLACES = PropertyDescriptor.builder()
		.name("indexedPricePlaces")
		.description("""
			Determines how many fractional places are important when entities are compared during filtering or sorting. It is
			important to know that all prices will be converted to `Integer`, so any of the price values
			(either with or without tax) must not ever exceed maximum limits of `Integer` type when scaling
			the number by the power of ten using `indexedPricePlaces` as exponent.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetEntitySchemaWithPriceMutation")
		.description("""
			Mutation is responsible for setting a `EntitySchema.withPrice`
			in `EntitySchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, WITH_PRICE, INDEXED_IN_SCOPES, INDEXED_PRICE_PLACES))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("SetEntitySchemaWithPriceMutationInput")
		.staticProperties(List.of(WITH_PRICE, INDEXED_IN_SCOPES, INDEXED_PRICE_PLACES))
		.build();
}
