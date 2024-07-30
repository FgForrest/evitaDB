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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcUpsertAssociatedDataMutation;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Converts between {@link UpsertAssociatedDataMutation} and {@link GrpcUpsertAssociatedDataMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UpsertAssociatedDataMutationConverter extends AssociatedDataMutationConverter<UpsertAssociatedDataMutation, GrpcUpsertAssociatedDataMutation> {
	public static final UpsertAssociatedDataMutationConverter INSTANCE = new UpsertAssociatedDataMutationConverter();

	@Override
	@Nonnull
	public UpsertAssociatedDataMutation convert(@Nonnull GrpcUpsertAssociatedDataMutation mutation) {
		final AssociatedDataKey key = buildAssociatedDataKey(mutation.getAssociatedDataName(), mutation.getAssociatedDataLocale());
		final Serializable targetTypeValue = EvitaDataTypesConverter.toEvitaValue(mutation.getAssociatedDataValue());
		return new UpsertAssociatedDataMutation(key, targetTypeValue);
	}

	@Override
	@Nonnull
	public GrpcUpsertAssociatedDataMutation convert(@Nonnull UpsertAssociatedDataMutation mutation) {
		final GrpcUpsertAssociatedDataMutation.Builder builder = GrpcUpsertAssociatedDataMutation.newBuilder()
			.setAssociatedDataName(mutation.getAssociatedDataKey().associatedDataName())
			.setAssociatedDataValue(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(mutation.getAssociatedDataValue()));

		if (mutation.getAssociatedDataKey().localized()) {
			builder.setAssociatedDataLocale(EvitaDataTypesConverter.toGrpcLocale(mutation.getAssociatedDataKey().locale()));
		}

		return builder.build();
	}
}
