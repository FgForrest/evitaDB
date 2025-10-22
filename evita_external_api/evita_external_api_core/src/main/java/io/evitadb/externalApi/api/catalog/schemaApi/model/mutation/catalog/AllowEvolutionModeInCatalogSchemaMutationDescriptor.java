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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface AllowEvolutionModeInCatalogSchemaMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor EVOLUTION_MODES = PropertyDescriptor.builder()
		.name("evolutionModes")
		.description("""
			Set of allowed catalog evolution modes. These allow to specify how strict is evitaDB when unknown
			information is presented to her for the first time. When no evolution mode is set, each violation of the
			`CatalogSchema` is reported by an error. This behaviour can be changed by this evolution mode, however.
			""")
		.type(nonNull(CatalogEvolutionMode[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AllowEvolutionModeInCatalogSchemaMutation")
		.description("""
			Mutation is responsible for adding one or more modes to a `CatalogSchema.catalogEvolutionMode`
			in `CatalogSchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, EVOLUTION_MODES))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("AllowEvolutionModeInCatalogSchemaMutationInput")
		.staticProperties(List.of(EVOLUTION_MODES))
		.build();
}
