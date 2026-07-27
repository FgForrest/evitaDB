/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.ConflictResolutionMutationConverterSupport;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link ModifyCatalogSchemaConflictResolutionMutation}.
 *
 * The nested {@link ConflictResolution} value object cannot be handled by the reflective converter path; both directions
 * are delegated to {@link ConflictResolutionMutationConverterSupport}, which documents the rationale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ModifyCatalogSchemaConflictResolutionMutationConverter
	extends LocalCatalogSchemaMutationConverter<ModifyCatalogSchemaConflictResolutionMutation> {

	public ModifyCatalogSchemaConflictResolutionMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<ModifyCatalogSchemaConflictResolutionMutation> getMutationClass() {
		return ModifyCatalogSchemaConflictResolutionMutation.class;
	}

	@Nonnull
	@Override
	protected ModifyCatalogSchemaConflictResolutionMutation convertFromInput(@Nonnull Input input) {
		final ConflictResolution conflictResolution = input.getOptionalProperty(
			ModifyCatalogSchemaConflictResolutionMutationDescriptor.CONFLICT_RESOLUTION.name(),
			rawValue -> ConflictResolutionMutationConverterSupport.parseConflictResolution(
				input, rawValue, getMutationName(), getExceptionFactory()
			)
		);
		return new ModifyCatalogSchemaConflictResolutionMutation(conflictResolution);
	}

	@Override
	protected void convertToOutput(
		@Nonnull ModifyCatalogSchemaConflictResolutionMutation mutation,
		@Nonnull Output output
	) {
		ConflictResolutionMutationConverterSupport.serializeConflictResolution(
			mutation.getConflictResolution(),
			output,
			ModifyCatalogSchemaConflictResolutionMutationDescriptor.CONFLICT_RESOLUTION.name()
		);
		super.convertToOutput(mutation, output);
	}
}
