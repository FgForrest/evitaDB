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

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaSearchableMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/** Converts fulltext searchability declarations, preserving optional analysis and ranking settings. */
public class SetAttributeSchemaSearchableMutationConverter
	extends AttributeSchemaMutationConverter<SetAttributeSchemaSearchableMutation> {
	/**
	 * Creates a converter with API-specific mapping and diagnostics.
	 *
	 * @param objectMapper API-specific parser for nested mutation objects
	 * @param exceptionFactory factory producing API-specific conversion diagnostics
	 */
	public SetAttributeSchemaSearchableMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetAttributeSchemaSearchableMutation> getMutationClass() {
		return SetAttributeSchemaSearchableMutation.class;
	}

	@Nonnull
	@Override
	protected SetAttributeSchemaSearchableMutation convertFromInput(@Nonnull Input input) {
		return new SetAttributeSchemaSearchableMutation(
			input.getProperty(SetAttributeSchemaSearchableMutationDescriptor.NAME),
			input.getProperty(SetAttributeSchemaSearchableMutationDescriptor.SEARCHABLE_IN_SCOPES),
			input.getProperty(SetAttributeSchemaSearchableMutationDescriptor.ANALYZER),
			input.getProperty(SetAttributeSchemaSearchableMutationDescriptor.LENGTH_PIVOT),
			input.getProperty(SetAttributeSchemaSearchableMutationDescriptor.SEARCH_WEIGHT)
		);
	}

	/**
	 * Writes the decimal ranking weight; reflection then fills in the remaining properties.
	 *
	 * @param mutation the mutation to serialize
	 * @param output the output receiving the properties
	 */
	@Override
	protected void convertToOutput(@Nonnull SetAttributeSchemaSearchableMutation mutation, @Nonnull Output output) {
		writeSearchWeight(
			SetAttributeSchemaSearchableMutationDescriptor.SEARCH_WEIGHT.name(),
			mutation.getSearchWeight(), output
		);
		super.convertToOutput(mutation, output);
	}
}
