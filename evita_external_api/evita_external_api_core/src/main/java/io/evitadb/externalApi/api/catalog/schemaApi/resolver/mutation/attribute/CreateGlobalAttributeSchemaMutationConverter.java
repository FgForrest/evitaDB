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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateGlobalAttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Implementation of {@link SchemaMutationConverter} for resolving {@link CreateGlobalAttributeSchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CreateGlobalAttributeSchemaMutationConverter extends AttributeSchemaMutationConverter<CreateGlobalAttributeSchemaMutation> {

	public CreateGlobalAttributeSchemaMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                                    @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<CreateGlobalAttributeSchemaMutation> getMutationClass() {
		return CreateGlobalAttributeSchemaMutation.class;
	}

	@Nonnull
	@Override
	protected CreateGlobalAttributeSchemaMutation convertFromInput(@Nonnull Input input) {
		final Class<? extends Serializable> valueType = input.getRequiredProperty(
			CreateGlobalAttributeSchemaMutationDescriptor.TYPE.name(),
			new ValueTypeMapper(getExceptionFactory(), CreateGlobalAttributeSchemaMutationDescriptor.TYPE)
		);

		return new CreateGlobalAttributeSchemaMutation(
			input.getProperty(AttributeSchemaMutationDescriptor.NAME),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE, false),
			valueType,
			input.getOptionalProperty(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), valueType),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES, 0)
		);
	}
}
