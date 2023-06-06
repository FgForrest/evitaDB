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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.InputMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.AssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.CreateAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link CreateAssociatedDataSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateAssociatedDataSchemaMutationConverter extends AssociatedDataSchemaMutationConverter<CreateAssociatedDataSchemaMutation> {

	public CreateAssociatedDataSchemaMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                                   @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return CreateAssociatedDataSchemaMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected CreateAssociatedDataSchemaMutation convert(@Nonnull InputMutation inputMutation) {
		return new CreateAssociatedDataSchemaMutation(
			inputMutation.getRequiredField(AssociatedDataSchemaMutationDescriptor.NAME),
			inputMutation.getOptionalField(CreateAssociatedDataSchemaMutationDescriptor.DESCRIPTION),
			inputMutation.getOptionalField(CreateAssociatedDataSchemaMutationDescriptor.DEPRECATION_NOTICE),
			inputMutation.getRequiredField(
				CreateAssociatedDataSchemaMutationDescriptor.TYPE.name(),
				new ValueTypeMapper(getExceptionFactory(), CreateAssociatedDataSchemaMutationDescriptor.TYPE)
			),
			inputMutation.getOptionalField(CreateAssociatedDataSchemaMutationDescriptor.LOCALIZED, false),
			inputMutation.getOptionalField(CreateAssociatedDataSchemaMutationDescriptor.NULLABLE, false)
		);
	}
}
