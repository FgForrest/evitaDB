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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PropertyObjectListMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
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
public class CreateGlobalAttributeSchemaMutationConverter
	extends AttributeSchemaMutationConverter<CreateGlobalAttributeSchemaMutation> {

	public CreateGlobalAttributeSchemaMutationConverter(
		@Nonnull MutationObjectMapper objectParser,
		@Nonnull MutationResolvingExceptionFactory exceptionFactory
	) {
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

		final ScopedAttributeUniquenessType[] uniqueInScopes = input.getOptionalProperty(
			CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_IN_SCOPES,
				ScopedAttributeUniquenessType.class,
				nestedInput -> new ScopedAttributeUniquenessType(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE)
				)
			)
		);
		final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes = input.getOptionalProperty(
			CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(),
			new PropertyObjectListMapper<>(
				getMutationName(),
				getExceptionFactory(),
				CreateGlobalAttributeSchemaMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES,
				ScopedGlobalAttributeUniquenessType.class,
				nestedInput -> new ScopedGlobalAttributeUniquenessType(
					nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
					nestedInput.getProperty(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE)
				)
			)
		);

		return new CreateGlobalAttributeSchemaMutation(
			input.getProperty(AttributeSchemaMutationDescriptor.NAME),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.DESCRIPTION),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.DEPRECATION_NOTICE),
			uniqueInScopes,
			uniqueGloballyInScopes,
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.FILTERABLE_IN_SCOPES),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.SORTABLE_IN_SCOPES),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.LOCALIZED, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.NULLABLE, false),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.REPRESENTATIVE, false),
			valueType,
			input.getOptionalProperty(CreateGlobalAttributeSchemaMutationDescriptor.DEFAULT_VALUE.name(), valueType),
			input.getProperty(CreateGlobalAttributeSchemaMutationDescriptor.INDEXED_DECIMAL_PLACES, 0)
		);
	}
}
