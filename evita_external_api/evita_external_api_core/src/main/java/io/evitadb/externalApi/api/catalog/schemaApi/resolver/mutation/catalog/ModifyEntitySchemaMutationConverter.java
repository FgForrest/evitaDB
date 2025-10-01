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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.EntitySchemaMutationAggregateConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link ModifyEntitySchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ModifyEntitySchemaMutationConverter
	extends LocalCatalogSchemaMutationConverter<ModifyEntitySchemaMutation> {

	@Nonnull
	private final EntitySchemaMutationAggregateConverter entitySchemaMutationAggregateResolver;
	@Nonnull
	private final DelegatingEntitySchemaMutationConverter delegatingEntitySchemaMutationConverter;

	public ModifyEntitySchemaMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
		this.entitySchemaMutationAggregateResolver = new EntitySchemaMutationAggregateConverter(
			objectMapper, exceptionFactory);
		this.delegatingEntitySchemaMutationConverter = new DelegatingEntitySchemaMutationConverter(
			objectMapper, exceptionFactory
		);
	}

	@Nonnull
	@Override
	protected Class<ModifyEntitySchemaMutation> getMutationClass() {
		return ModifyEntitySchemaMutation.class;
	}

	@Nonnull
	@Override
	protected ModifyEntitySchemaMutation convertFromInput(@Nonnull Input input) {
		final List<Object> inputEntitySchemaMutations = Optional
			.of(
				input.getRequiredProperty(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name()))
			.map(m -> {
				Assert.isTrue(
					m instanceof List<?>,
					() -> getExceptionFactory().createInvalidArgumentException(
						"Field `" + ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name() + "` of mutation `" + getMutationName() + "` is expected to be a list.")
				);
				//noinspection unchecked
				return (List<Object>) m;
			})
			.get();
		final LocalEntitySchemaMutation[] entitySchemaMutations = inputEntitySchemaMutations
			.stream()
			.flatMap(
				m -> this.entitySchemaMutationAggregateResolver.convertFromInput(
					m).stream())
			.toArray(
				LocalEntitySchemaMutation[]::new);

		return new ModifyEntitySchemaMutation(
			input.getProperty(ModifyEntitySchemaMutationDescriptor.ENTITY_TYPE),
			entitySchemaMutations
		);
	}

	@Override
	protected void convertToOutput(@Nonnull ModifyEntitySchemaMutation mutation, @Nonnull Output output) {
		output.setProperty(
			ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS,
			this.delegatingEntitySchemaMutationConverter.convertToOutput(mutation.getSchemaMutations())
		);
		super.convertToOutput(mutation, output);
	}
}
