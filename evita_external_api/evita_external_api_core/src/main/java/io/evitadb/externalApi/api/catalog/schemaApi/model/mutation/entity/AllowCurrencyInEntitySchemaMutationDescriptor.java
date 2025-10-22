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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.Currency;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.entity.AllowCurrencyInEntitySchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface AllowCurrencyInEntitySchemaMutationDescriptor extends EntitySchemaMutationDescriptor {

	PropertyDescriptor CURRENCIES = PropertyDescriptor.builder()
		.name("currencies")
		.description("""
			Set of all currencies that could be used for prices in entities of this type.
			""")
		.type(nonNull(Currency[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AllowCurrencyInEntitySchemaMutation")
		.description("""
			Mutation is responsible for adding one or more currencies to a `EntitySchema.currencies`
			in `EntitySchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, CURRENCIES))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("AllowCurrencyInEntitySchemaMutationInput")
		.staticProperties(List.of(CURRENCIES))
		.build();
}
