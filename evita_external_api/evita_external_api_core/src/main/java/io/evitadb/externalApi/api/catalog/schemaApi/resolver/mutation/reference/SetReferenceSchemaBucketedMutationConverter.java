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

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaBucketedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link SetReferenceSchemaBucketedMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SetReferenceSchemaBucketedMutationConverter
	extends ReferenceSchemaMutationConverter<SetReferenceSchemaBucketedMutation> {

	public SetReferenceSchemaBucketedMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<SetReferenceSchemaBucketedMutation> getMutationClass() {
		return SetReferenceSchemaBucketedMutation.class;
	}

	@Nonnull
	@Override
	protected SetReferenceSchemaBucketedMutation convertFromInput(@Nonnull Input input) {
		final ScopedHistogramIndexDefinition[] bucketedInScopes = parseBucketedHistogram(
			input,
			SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES
		);

		final ScopedBucketedPartially[] bucketedPartiallyInScopes = parseBucketedPartially(
			input,
			SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES
		);

		return new SetReferenceSchemaBucketedMutation(
			input.getProperty(ReferenceSchemaMutationDescriptor.NAME),
			bucketedInScopes,
			bucketedPartiallyInScopes
		);
	}

	@Override
	protected void convertToOutput(
		@Nonnull SetReferenceSchemaBucketedMutation mutation,
		@Nonnull Output output
	) {
		serializeBucketedHistogram(mutation.getBucketedInScopes(), output);
		serializeBucketedPartially(mutation.getBucketedPartiallyInScopes(), output);
		super.convertToOutput(mutation, output);
	}
}
