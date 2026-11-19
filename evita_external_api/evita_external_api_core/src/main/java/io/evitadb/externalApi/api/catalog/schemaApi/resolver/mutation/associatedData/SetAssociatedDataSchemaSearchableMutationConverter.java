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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaSearchableMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/** Converts fulltext searchability declarations, preserving optional analysis and ranking settings. */
public class SetAssociatedDataSchemaSearchableMutationConverter
	extends AssociatedDataSchemaMutationConverter<SetAssociatedDataSchemaSearchableMutation> {
	/**
	 * Creates a converter with API-specific mapping and diagnostics.
	 *
	 * @param objectMapper API-specific parser for nested mutation objects
	 * @param exceptionFactory factory producing API-specific conversion diagnostics
	 */
	public SetAssociatedDataSchemaSearchableMutationConverter(
		@Nonnull MutationObjectMapper objectMapper,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectMapper, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetAssociatedDataSchemaSearchableMutation> getMutationClass() {
		return SetAssociatedDataSchemaSearchableMutation.class;
	}

	@Nonnull
	@Override
	protected SetAssociatedDataSchemaSearchableMutation convertFromInput(@Nonnull Input input) {
		return new SetAssociatedDataSchemaSearchableMutation(
			input.getProperty(SetAssociatedDataSchemaSearchableMutationDescriptor.NAME),
			input.getProperty(SetAssociatedDataSchemaSearchableMutationDescriptor.SEARCHABLE_IN_SCOPES),
			readTextExpression(input),
			input.getProperty(SetAssociatedDataSchemaSearchableMutationDescriptor.ANALYZER),
			input.getProperty(SetAssociatedDataSchemaSearchableMutationDescriptor.LENGTH_PIVOT),
			input.getProperty(SetAssociatedDataSchemaSearchableMutationDescriptor.SEARCH_WEIGHT)
		);
	}

	/**
	 * Writes the text expression as raw source and the ranking weight as a decimal before reflection reaches them,
	 * because {@link Output} cannot serialize an `Expression`; reflection then fills in the
	 * remaining properties.
	 *
	 * @param mutation the mutation to serialize
	 * @param output the output receiving the properties
	 */
	@Override
	protected void convertToOutput(
		@Nonnull SetAssociatedDataSchemaSearchableMutation mutation,
		@Nonnull Output output
	) {
		writeTextExpression(mutation.getTextExpression(), output);
		writeSearchWeight(
			SetAssociatedDataSchemaSearchableMutationDescriptor.SEARCH_WEIGHT.name(),
			mutation.getSearchWeight(), output
		);
		super.convertToOutput(mutation, output);
	}
}
