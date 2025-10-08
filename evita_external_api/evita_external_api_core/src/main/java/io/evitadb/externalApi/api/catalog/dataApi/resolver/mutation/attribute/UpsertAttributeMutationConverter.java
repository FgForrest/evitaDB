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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.model.mutation.MutationConverterContext;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link UpsertAttributeMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class UpsertAttributeMutationConverter extends AttributeMutationConverter<UpsertAttributeMutation> {

	public UpsertAttributeMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                                        @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<UpsertAttributeMutation> getMutationClass() {
		return UpsertAttributeMutation.class;
	}

	@Nonnull
	@Override
	protected UpsertAttributeMutation convertFromInput(@Nonnull Input input) {
		final AttributeKey attributeKey = resolveAttributeKey(input);

		final Class<? extends Serializable> valueType = input.getOptionalProperty(
			UpsertAttributeMutationDescriptor.VALUE_TYPE.name(),
			new ValueTypeMapper(getExceptionFactory(), UpsertAttributeMutationDescriptor.VALUE_TYPE)
		);
		final AttributeSchemaProvider<?> attributeSchemaProvider = input.getContextValue(MutationConverterContext.ATTRIBUTE_SCHEMA_PROVIDER_KEY);
		Assert.isPremiseValid(
			attributeSchemaProvider != null,
			() -> getExceptionFactory().createInternalError("Attribute schema provider is required for conversion from input.")
		);
		final AttributeSchemaContract attributeSchema = attributeSchemaProvider.getAttribute(attributeKey.attributeName()).orElse(null);
		if (attributeSchema == null && valueType == null) {
			throw getExceptionFactory().createInvalidArgumentException("Missing value type of new attribute `" + attributeKey.attributeName() + "`.");
		}
		if (attributeSchema != null && valueType != null) {
			Assert.isTrue(
				attributeSchema.getType().equals(valueType),
				() -> getExceptionFactory().createInvalidArgumentException("Value type does not correspond with data type in attribute schema `" + attributeKey.attributeName() + "`.")
			);
		}
		final Class<? extends Serializable> targetDataType = valueType != null ? valueType : attributeSchema.getType();

		final Serializable targetValue = input.getRequiredProperty(UpsertAttributeMutationDescriptor.VALUE.name(), targetDataType);
		return new UpsertAttributeMutation(attributeKey, targetValue);
	}

	@Override
	protected void convertToOutput(@Nonnull UpsertAttributeMutation mutation, @Nonnull Output output) {
		output.setProperty(UpsertAttributeMutationDescriptor.VALUE, mutation.getAttributeValue());
		output.setProperty(UpsertAttributeMutationDescriptor.VALUE_TYPE, mutation.getAttributeValue().getClass());
		super.convertToOutput(mutation, output);
	}
}
