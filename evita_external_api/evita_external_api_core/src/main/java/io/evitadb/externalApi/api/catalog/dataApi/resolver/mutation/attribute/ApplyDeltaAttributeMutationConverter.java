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
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.model.mutation.MutationConverterContext;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link ApplyDeltaAttributeMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ApplyDeltaAttributeMutationConverter extends AttributeMutationConverter<ApplyDeltaAttributeMutation<?>> {

	public ApplyDeltaAttributeMutationConverter(@Nonnull MutationObjectMapper objectParser,
	                                            @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected Class<ApplyDeltaAttributeMutation<?>> getMutationClass() {
		//noinspection unchecked
		return (Class<ApplyDeltaAttributeMutation<?>>) ((Class<?>) ApplyDeltaAttributeMutation.class);
	}

	@Nonnull
	@Override
	protected ApplyDeltaAttributeMutation<?> convertFromInput(@Nonnull Input input) {
		final AttributeKey attributeKey = resolveAttributeKey(input);

		final AttributeSchemaProvider<?> attributeSchemaProvider = input.getContextValue(MutationConverterContext.ATTRIBUTE_SCHEMA_PROVIDER_KEY);
		Assert.isPremiseValid(
			attributeSchemaProvider != null,
			() -> getExceptionFactory().createInternalError("Attribute schema provider is required for conversion from input.")
		);
		final AttributeSchemaContract attributeSchema = attributeSchemaProvider.getAttribute(attributeKey.attributeName())
			.orElseThrow(() -> getExceptionFactory().createInvalidArgumentException("Missing value type of new attribute `" + attributeKey.attributeName() + "`."));
		final Class<? extends Serializable> valueType = attributeSchema.getType();

		if (valueType.isAssignableFrom(BigDecimal.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredProperty(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), BigDecimal.class),
				input.getOptionalProperty(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), BigDecimalNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Byte.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredProperty(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Byte.class),
				input.getOptionalProperty(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), ByteNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Short.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredProperty(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Short.class),
				input.getOptionalProperty(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), ShortNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Integer.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredProperty(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Integer.class),
				input.getOptionalProperty(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), IntegerNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Long.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredProperty(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Long.class),
				input.getOptionalProperty(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), LongNumberRange.class)
			);
		} else {
			throw getExceptionFactory().createInvalidArgumentException("Attribute `" + attributeKey.attributeName() + "` supports only numbers.");
		}
	}

	@Override
	protected void convertToOutput(@Nonnull ApplyDeltaAttributeMutation<?> mutation, @Nonnull Output output) {
		output.setProperty(ApplyDeltaAttributeMutationDescriptor.DELTA, mutation.getDelta());
		output.setProperty(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION, mutation.getRequiredRangeAfterApplication());
		super.convertToOutput(mutation, output);
	}
}
