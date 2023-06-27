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
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link ApplyDeltaAttributeMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ApplyDeltaAttributeMutationConverter extends AttributeMutationConverter<ApplyDeltaAttributeMutation<?>> {

	@Nonnull
	private final AttributeSchemaProvider<?> attributeSchemaProvider;

	public ApplyDeltaAttributeMutationConverter(@Nonnull AttributeSchemaProvider<?> attributeSchemaProvider,
	                                            @Nonnull MutationObjectParser objectParser,
	                                            @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
		this.attributeSchemaProvider = attributeSchemaProvider;
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return ApplyDeltaAttributeMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected ApplyDeltaAttributeMutation<?> convert(@Nonnull Input input) {
		final AttributeKey attributeKey = resolveAttributeKey(input);

		final AttributeSchemaContract attributeSchema = attributeSchemaProvider.getAttribute(attributeKey.getAttributeName())
			.orElseThrow(() -> getExceptionFactory().createInvalidArgumentException("Missing value type of new attribute `" + attributeKey.getAttributeName() + "`."));
		final Class<? extends Serializable> valueType = attributeSchema.getType();

		if (valueType.isAssignableFrom(BigDecimal.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredField(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), BigDecimal.class),
				input.getOptionalField(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), BigDecimalNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Byte.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredField(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Byte.class),
				input.getOptionalField(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), ByteNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Short.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredField(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Short.class),
				input.getOptionalField(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), ShortNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Integer.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredField(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Integer.class),
				input.getOptionalField(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), IntegerNumberRange.class)
			);
		} else if (valueType.isAssignableFrom(Long.class)) {
			return new ApplyDeltaAttributeMutation<>(
				attributeKey,
				input.getRequiredField(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), Long.class),
				input.getOptionalField(ApplyDeltaAttributeMutationDescriptor.REQUIRED_RANGE_AFTER_APPLICATION.name(), LongNumberRange.class)
			);
		} else {
			throw getExceptionFactory().createInvalidArgumentException("Attribute `" + attributeKey.getAttributeName() + "` supports only numbers.");
		}
	}
}
