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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.DelegatingAttributeMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.AttributeMutationInputAggregateConverter;
import io.evitadb.externalApi.api.model.mutation.MutationConverterContext;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link ReferenceAttributeMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ReferenceAttributeMutationConverter extends ReferenceMutationConverter<ReferenceAttributeMutation> {

	@Nonnull
	private final AttributeMutationInputAggregateConverter attributeMutationAggregateConverter;
	@Nonnull
	private final DelegatingAttributeMutationConverter delegatingAttributeMutationConverter;

	public ReferenceAttributeMutationConverter(@Nonnull MutationObjectMapper objectMapper,
	                                           @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectMapper, exceptionFactory);
		this.attributeMutationAggregateConverter = new AttributeMutationInputAggregateConverter(objectMapper, exceptionFactory);
		this.delegatingAttributeMutationConverter = new DelegatingAttributeMutationConverter(objectMapper, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<ReferenceAttributeMutation> getMutationClass() {
		return ReferenceAttributeMutation.class;
	}

	@Nonnull
	@Override
	protected ReferenceAttributeMutation convertFromInput(@Nonnull Input input) {
		final ReferenceKey referenceKey = resolveReferenceKey(input);
		final Map<String, Object> inputAttributeMutation = Optional.of(input.getRequiredProperty(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name()))
			.map(m -> {
				Assert.isTrue(
					m instanceof Map<?, ?>,
					() -> getExceptionFactory().createInvalidArgumentException("Field `" + ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name() + "` of mutation `" + getMutationName() + "` is expected to be object.")
				);
				//noinspection unchecked
				return (Map<String, Object>) m;
			})
			.get();
		Assert.isTrue(
			inputAttributeMutation.size() == 1,
			() -> getExceptionFactory().createInvalidArgumentException("`ReferenceAttributesUpdateMutation` supports only one attribute mutation inside.")
		);

		final EntitySchemaContract entitySchema = input.getContextValue(MutationConverterContext.ENTITY_SCHEMA_KEY);
		Assert.isPremiseValid(
			entitySchema != null,
			() -> getExceptionFactory().createInternalError("Entity schema is required for conversion from input.")
		);
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(
			referenceKey.referenceName());
		final List<AttributeMutation> attributeMutations = this.attributeMutationAggregateConverter.convertFromInput(
			inputAttributeMutation,
			input.createChildContext(Map.of(
				MutationConverterContext.ATTRIBUTE_SCHEMA_PROVIDER_KEY, referenceSchema
			))
		);
		Assert.isTrue(
			attributeMutations.size() == 1,
			() -> getExceptionFactory().createInvalidArgumentException("Field `" + ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name() + "` in mutation `" + getMutationName() + "` is required and is expected to have exactly one mutation")
		);

		return new ReferenceAttributeMutation(referenceKey, attributeMutations.get(0));
	}

	@Override
	protected void convertToOutput(@Nonnull ReferenceAttributeMutation mutation, @Nonnull Output output) {
		output.setProperty(
			ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION,
			this.delegatingAttributeMutationConverter.convertToOutput(mutation.getAttributeMutation())
		);
		super.convertToOutput(mutation, output);
	}
}
