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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ModifyReferenceAttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingAttributeSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.AttributeSchemaMutationAggregateConverter;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link ModifyReferenceAttributeSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ModifyReferenceAttributeSchemaMutationConverter
	extends ReferenceSchemaMutationConverter<ModifyReferenceAttributeSchemaMutation> {

	@Nonnull
	private final AttributeSchemaMutationAggregateConverter attributeSchemaMutationAggregateResolver;
	@Nonnull
	private final DelegatingAttributeSchemaMutationConverter delegatingAttributeSchemaMutationConverter;

	public ModifyReferenceAttributeSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
		this.attributeSchemaMutationAggregateResolver = new AttributeSchemaMutationAggregateConverter(
			objectMapper, exceptionFactory);
		this.delegatingAttributeSchemaMutationConverter = new DelegatingAttributeSchemaMutationConverter(
			objectMapper, exceptionFactory
		);
	}

	@Nonnull
	@Override
	protected Class<ModifyReferenceAttributeSchemaMutation> getMutationClass() {
		return ModifyReferenceAttributeSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected ModifyReferenceAttributeSchemaMutation convertFromInput(@Nonnull Input input) {
		final Map<String, Object> inputAttributeSchemaMutation = Optional
			.of(input.getRequiredProperty(
				ModifyReferenceAttributeSchemaMutationDescriptor.ATTRIBUTE_SCHEMA_MUTATION.name()))
			.map(m -> {
				Assert.isTrue(
					m instanceof Map<?, ?>,
					() -> getExceptionFactory().createInvalidArgumentException(
						"Field `" + ModifyReferenceAttributeSchemaMutationDescriptor.ATTRIBUTE_SCHEMA_MUTATION.name() + "` of mutation `" + getMutationName() + "` is expected to be an object.")
				);
				//noinspection unchecked
				return (Map<String, Object>) m;
			})
			.get();
		final List<AttributeSchemaMutation> attributeSchemaMutations = this.attributeSchemaMutationAggregateResolver
			.convertFromInput(inputAttributeSchemaMutation);
		Assert.isTrue(
			attributeSchemaMutations.size() == 1,
			() -> getExceptionFactory().createInvalidArgumentException(
				"Field `" + ModifyReferenceAttributeSchemaMutationDescriptor.ATTRIBUTE_SCHEMA_MUTATION.name() + "` in mutation `" + getMutationName() + "` is required and is expected to have exactly one mutation")
		);
		Assert.isTrue(
			attributeSchemaMutations.get(0) instanceof ReferenceAttributeSchemaMutation,
			() -> getExceptionFactory().createInvalidArgumentException(
				"Field `" + ModifyReferenceAttributeSchemaMutationDescriptor.ATTRIBUTE_SCHEMA_MUTATION.name() + "` in mutation `" + getMutationName() + "` isn't supported by reference attributes.")
		);

		return new ModifyReferenceAttributeSchemaMutation(
			input.getProperty(ReferenceSchemaMutationDescriptor.NAME),
			(ReferenceAttributeSchemaMutation) attributeSchemaMutations.get(0)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull ModifyReferenceAttributeSchemaMutation mutation, @Nonnull Output output) {
		output.setProperty(
			ModifyReferenceAttributeSchemaMutationDescriptor.ATTRIBUTE_SCHEMA_MUTATION,
			this.delegatingAttributeSchemaMutationConverter.convertToOutput(
				mutation.getAttributeSchemaMutation())
		);
		super.convertToOutput(mutation, output);
	}
}
