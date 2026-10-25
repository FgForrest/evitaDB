/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Converts complete normalization replacement statements for entity, reference and global attributes.
 * Inputs use installed-provider selectors; outputs use ordered step descriptions. An omitted or empty chain clears
 * the declaration, while retained built-with index policies remain internal to the engine.
 */
public class SetAttributeSchemaNormalizedMutationConverter
	extends AttributeSchemaMutationConverter<SetAttributeSchemaNormalizedMutation> {

	/**
	 * Creates the converter using the API's object mapper and diagnostics.
	 *
	 * @param objectMapper API-specific parser for nested mutation objects
	 * @param exceptionFactory factory producing API-specific conversion diagnostics
	 */
	public SetAttributeSchemaNormalizedMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetAttributeSchemaNormalizedMutation> getMutationClass() {
		return SetAttributeSchemaNormalizedMutation.class;
	}

	@Nonnull
	@Override
	protected SetAttributeSchemaNormalizedMutation convertFromInput(@Nonnull Input input) {
		return new SetAttributeSchemaNormalizedMutation(
			input.getProperty(AttributeSchemaMutationDescriptor.NAME), readNormalization(input)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull SetAttributeSchemaNormalizedMutation mutation, @Nonnull Output output) {
		writeNormalization(mutation.getNormalization(), output);
		super.convertToOutput(mutation, output);
	}
}
