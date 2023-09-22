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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link DisallowEvolutionModeInCatalogSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DisallowEvolutionModeInCatalogSchemaMutationConverter extends LocalCatalogSchemaMutationConverter<DisallowEvolutionModeInCatalogSchemaMutation> {

	public DisallowEvolutionModeInCatalogSchemaMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                                             @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<DisallowEvolutionModeInCatalogSchemaMutation> getMutationClass() {
		return DisallowEvolutionModeInCatalogSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected DisallowEvolutionModeInCatalogSchemaMutation convertFromInput(@Nonnull Input input) {
		// there are 2 constructors, which we don't support currently
		return new DisallowEvolutionModeInCatalogSchemaMutation(
			(CatalogEvolutionMode[]) input.getProperty(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull DisallowEvolutionModeInCatalogSchemaMutation mutation, @Nonnull Output output) {
		// there are 2 constructors, which we don't support currently
		output.setProperty(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES, mutation.getEvolutionModes());
	}
}
