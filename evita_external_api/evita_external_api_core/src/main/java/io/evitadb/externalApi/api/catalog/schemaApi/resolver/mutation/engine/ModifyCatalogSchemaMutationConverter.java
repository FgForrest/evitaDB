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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.ModifyCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.EngineMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.LocalCatalogSchemaMutationInputAggregateConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.LocalCatalogSchemaMutationConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link LocalCatalogSchemaMutationConverter} for resolving {@link ModifyCatalogSchemaMutation}.
 * This converter handles the conversion of external API requests into catalog schema modification mutations,
 * enabling updates to existing catalog schema properties through the external API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ModifyCatalogSchemaMutationConverter
	extends EngineMutationConverter<ModifyCatalogSchemaMutation> {

	@Nonnull
	private final LocalCatalogSchemaMutationInputAggregateConverter localCatalogSchemaMutationAggregateConverter;
	@Nonnull
	private final DelegatingLocalCatalogSchemaMutationConverter delegatingLocalCatalogSchemaMutationConverter;

	public ModifyCatalogSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
		this.localCatalogSchemaMutationAggregateConverter = new LocalCatalogSchemaMutationInputAggregateConverter(
			objectMapper, exceptionFactory);
		this.delegatingLocalCatalogSchemaMutationConverter = new DelegatingLocalCatalogSchemaMutationConverter(
			objectMapper, exceptionFactory
		);
	}

	@Nonnull
	@Override
	protected Class<ModifyCatalogSchemaMutation> getMutationClass() {
		return ModifyCatalogSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected ModifyCatalogSchemaMutation convertFromInput(@Nonnull Input input) {
		final List<Object> inputEntitySchemaMutations = Optional
			.of(
				input.getRequiredProperty(ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS.name()))
			.map(m -> {
				Assert.isTrue(
					m instanceof List<?>,
					() -> getExceptionFactory().createInvalidArgumentException(
						"Field `" + ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS.name() + "` of mutation `" + getMutationName() + "` is expected to be a list.")
				);
				//noinspection unchecked
				return (List<Object>) m;
			})
			.get();
		final LocalCatalogSchemaMutation[] localCatalogSchemaMutations = inputEntitySchemaMutations
			.stream()
			.flatMap(
				m -> this.localCatalogSchemaMutationAggregateConverter.convertFromInput(
					m).stream())
			.toArray(
				LocalCatalogSchemaMutation[]::new);

		return new ModifyCatalogSchemaMutation(
			input.getProperty(EngineMutationDescriptor.CATALOG_NAME),
			null,
			localCatalogSchemaMutations
		);
	}

	@Override
	protected void convertToOutput(@Nonnull ModifyCatalogSchemaMutation mutation, @Nonnull Output output) {
		output.setProperty(
			ModifyCatalogSchemaMutationDescriptor.SCHEMA_MUTATIONS,
			this.delegatingLocalCatalogSchemaMutationConverter.convertToOutput(mutation.getSchemaMutations())
		);
		super.convertToOutput(mutation, output);
	}
}
