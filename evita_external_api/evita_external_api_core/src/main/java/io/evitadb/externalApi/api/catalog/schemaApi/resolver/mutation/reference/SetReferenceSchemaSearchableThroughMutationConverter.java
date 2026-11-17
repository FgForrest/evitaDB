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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaSearchableThroughMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/** Converts reference composition declarations while preserving absent modes and weights. */
public class SetReferenceSchemaSearchableThroughMutationConverter
	extends ReferenceSchemaMutationConverter<SetReferenceSchemaSearchableThroughMutation> {
	/**
	 * Creates a converter with API-specific mapping and diagnostics.
	 *
	 * @param objectMapper API-specific parser for nested mutation objects
	 * @param exceptionFactory factory producing API-specific conversion diagnostics
	 */
	public SetReferenceSchemaSearchableThroughMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetReferenceSchemaSearchableThroughMutation> getMutationClass() {
		return SetReferenceSchemaSearchableThroughMutation.class;
	}

	@Nonnull
	@Override
	protected SetReferenceSchemaSearchableThroughMutation convertFromInput(@Nonnull Input input) {
		return new SetReferenceSchemaSearchableThroughMutation(
			input.getProperty(SetReferenceSchemaSearchableThroughMutationDescriptor.NAME),
			readSearchableThrough(input),
			input.getProperty(SetReferenceSchemaSearchableThroughMutationDescriptor.WEIGHT)
		);
	}

	/**
	 * Writes the shared decimal weight; reflection then fills in the composition modes.
	 *
	 * @param mutation the mutation to serialize
	 * @param output the output receiving the properties
	 */
	@Override
	protected void convertToOutput(
		@Nonnull SetReferenceSchemaSearchableThroughMutation mutation, @Nonnull Output output
	) {
		writeSearchWeight(
			SetReferenceSchemaSearchableThroughMutationDescriptor.WEIGHT.name(), mutation.getWeight(), output
		);
		super.convertToOutput(mutation, output);
	}
}
