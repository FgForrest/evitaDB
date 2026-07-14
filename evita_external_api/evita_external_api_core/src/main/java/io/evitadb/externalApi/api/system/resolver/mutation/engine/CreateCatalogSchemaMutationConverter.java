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

package io.evitadb.externalApi.api.system.resolver.mutation.engine;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.ConflictResolutionMutationConverterSupport;
import io.evitadb.externalApi.api.system.model.mutation.engine.CreateCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.system.model.mutation.engine.EngineMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.api.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link EngineMutationConverter} for resolving {@link CreateCatalogSchemaMutation}.
 * This converter handles the conversion of external API requests into catalog schema creation mutations,
 * enabling the creation of new catalog schemas through the external API.
 *
 * The optional catalog-level {@link ConflictResolution} value object cannot be handled by the reflective converter
 * path; both directions are delegated to {@link ConflictResolutionMutationConverterSupport}, which documents the
 * rationale.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateCatalogSchemaMutationConverter
	extends EngineMutationConverter<CreateCatalogSchemaMutation> {

	public CreateCatalogSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<CreateCatalogSchemaMutation> getMutationClass() {
		return CreateCatalogSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected CreateCatalogSchemaMutation convertFromInput(@Nonnull Input input) {
		final String catalogName = input.getProperty(EngineMutationDescriptor.CATALOG_NAME);
		final ConflictResolution conflictResolution = input.getOptionalProperty(
			CreateCatalogSchemaMutationDescriptor.CONFLICT_RESOLUTION.name(),
			rawValue -> ConflictResolutionMutationConverterSupport.parseConflictResolution(
				input, rawValue, getMutationName(), getExceptionFactory()
			)
		);
		return new CreateCatalogSchemaMutation(catalogName, conflictResolution);
	}

	@Override
	protected void convertToOutput(
		@Nonnull CreateCatalogSchemaMutation mutation,
		@Nonnull Output output
	) {
		ConflictResolutionMutationConverterSupport.serializeConflictResolution(
			mutation.getConflictResolution(),
			output,
			CreateCatalogSchemaMutationDescriptor.CONFLICT_RESOLUTION.name()
		);
		super.convertToOutput(mutation, output);
	}
}
