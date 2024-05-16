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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
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
public class ModifyEntitySchemaMutationConverter extends CatalogSchemaMutationConverter<ModifyEntitySchemaMutation> {

	@Nonnull
	private final EntitySchemaMutationAggregateConverter entitySchemaMutationAggregateResolver;

	public ModifyEntitySchemaMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                           @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
		this.entitySchemaMutationAggregateResolver = new EntitySchemaMutationAggregateConverter(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return ModifyEntitySchemaMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected ModifyEntitySchemaMutation convert(@Nonnull Input input) {
		final List<Object> inputEntitySchemaMutations = Optional.of(input.getRequiredField(ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name()))
			.map(m -> {
				Assert.isTrue(
					m instanceof List<?>,
					() -> getExceptionFactory().createInvalidArgumentException("Field `" + ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name() + "` of mutation `" + getMutationName() + "` is expected to be a list.")
				);
				//noinspection unchecked
				return (List<Object>) m;
			})
			.get();
		final EntitySchemaMutation[] entitySchemaMutations = inputEntitySchemaMutations.stream()
			.flatMap(m -> entitySchemaMutationAggregateResolver.convert(m).stream())
			.toArray(EntitySchemaMutation[]::new);

		return new ModifyEntitySchemaMutation(
			input.getRequiredField(ModifyEntitySchemaMutationDescriptor.ENTITY_TYPE),
			entitySchemaMutations
		);
	}
}
