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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation;

import javax.annotation.Nonnull;

/**
 * Converts between {@link RemoveAttributeMutation} and {@link GrpcRemoveAttributeMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RemoveAttributeMutationConverter extends AttributeMutationConverter<RemoveAttributeMutation, GrpcRemoveAttributeMutation> {

	@Override
	@Nonnull
	public RemoveAttributeMutation convert(@Nonnull GrpcRemoveAttributeMutation mutation) {
		final AttributesContract.AttributeKey key = buildAttributeKey(mutation.getAttributeName(), mutation.getAttributeLocale());
		return new RemoveAttributeMutation(key);
	}

	@Nonnull
	@Override
	public GrpcRemoveAttributeMutation convert(@Nonnull RemoveAttributeMutation mutation) {
		final GrpcRemoveAttributeMutation.Builder builder = GrpcRemoveAttributeMutation.newBuilder()
			.setAttributeName(mutation.getAttributeKey().attributeName());

		if (mutation.getAttributeKey().localized()) {
			builder.setAttributeLocale(EvitaDataTypesConverter.toGrpcLocale(mutation.getAttributeKey().locale()));
		}

		return builder.build();
	}
}
