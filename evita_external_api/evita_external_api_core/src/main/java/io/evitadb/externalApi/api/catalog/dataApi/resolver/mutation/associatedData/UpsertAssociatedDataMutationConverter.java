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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.JsonToComplexDataObjectConverter;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.ValueTypeMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.FieldObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Optional;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link UpsertAssociatedDataMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class UpsertAssociatedDataMutationConverter extends AssociatedDataMutationConverter<UpsertAssociatedDataMutation> {

	@Nonnull
	private final JsonToComplexDataObjectConverter jsonToComplexDataObjectConverter;
	@Nonnull
	private final EntitySchemaContract entitySchema;

	public UpsertAssociatedDataMutationConverter(@Nonnull ObjectMapper objectMapper,
	                                             @Nonnull EntitySchemaContract entitySchema,
	                                             @Nonnull MutationObjectParser objectParser,
	                                             @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
		this.jsonToComplexDataObjectConverter = new JsonToComplexDataObjectConverter(objectMapper);
		this.entitySchema = entitySchema;
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return UpsertAssociatedDataMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected UpsertAssociatedDataMutation convert(@Nonnull Input input) {
		final AssociatedDataKey associatedDataKey = resolveAssociatedDataKey(input);

		final Class<? extends Serializable> valueType = input.getOptionalField(
			UpsertAssociatedDataMutationDescriptor.VALUE_TYPE.name(),
			new ValueTypeMapper(getExceptionFactory(), UpsertAssociatedDataMutationDescriptor.VALUE_TYPE)
		);

		final Optional<AssociatedDataSchemaContract> associatedDataSchema = entitySchema.getAssociatedData(associatedDataKey.associatedDataName());
		if (associatedDataSchema.isEmpty() && valueType == null) {
			throw getExceptionFactory().createInvalidArgumentException("Missing value type of new associated data `" + associatedDataKey.associatedDataName() + "`.");
		}
		if (associatedDataSchema.isPresent() && valueType != null) {
			Assert.isTrue(
				associatedDataSchema.get().getType().equals(valueType),
				() -> getExceptionFactory().createInvalidArgumentException("Value type does not correspond with data type in associated dat schema `" + associatedDataKey.associatedDataName() + "`.")
			);
		}

		final Serializable targetValue;
		final Class<? extends Serializable> targetDataType = valueType != null ? valueType : associatedDataSchema.get().getType();

		if (targetDataType.equals(ComplexDataObject.class)) {
			targetValue = input.getRequiredField(
				UpsertAssociatedDataMutationDescriptor.VALUE.name(),
				new FieldObjectMapper<>(
					getMutationName(),
					getExceptionFactory(),
					UpsertAssociatedDataMutationDescriptor.VALUE,
					nestedInput -> {
						try {
							return jsonToComplexDataObjectConverter.fromMap(nestedInput.getRequiredValue());
						} catch (JsonProcessingException e) {
							throw getExceptionFactory().createInvalidArgumentException("Could not parse input JSON.");
						}
					}
				)
			);
		} else {
			targetValue = input.getRequiredField(UpsertAssociatedDataMutationDescriptor.VALUE.name(), targetDataType);
		}

		return new UpsertAssociatedDataMutation(associatedDataKey, targetValue);
	}
}
