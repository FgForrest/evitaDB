/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcLocale;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Ancestor for all converters converting implementations of {@link AttributeMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AttributeMutationConverter<J extends AttributeMutation, G extends Message> implements LocalMutationConverter<J, G> {

	/**
	 * This method extracts and builds attribute key from passed {@code attributeName} and {@link GrpcLocale} to Evita {@link AttributesContract.AttributeKey}.
	 * When localization was specified, it is converted to {@link AttributesContract.AttributeKey} with localization.
	 * Otherwise, it is converted to global {@link AttributesContract.AttributeKey} without localization.
	 *
	 * @param attributeName   name of the attribute
	 * @param attributeLocale locale of the attribute which might be equal to the default instance of {@link GrpcLocale} when no localization was specified
	 * @return built attribute key
	 */
	@Nonnull
	protected static AttributesContract.AttributeKey buildAttributeKey(@Nonnull String attributeName, @Nonnull GrpcLocale attributeLocale) {
		if (!attributeLocale.getDefaultInstanceForType().equals(attributeLocale)) {
			return new AttributeKey(
				attributeName,
				EvitaDataTypesConverter.toLocale(attributeLocale)
			);
		}
		return new AttributeKey(attributeName);
	}
}
