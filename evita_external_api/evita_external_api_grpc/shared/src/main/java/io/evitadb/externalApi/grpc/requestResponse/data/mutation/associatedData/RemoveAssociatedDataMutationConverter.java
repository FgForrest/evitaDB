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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutation;

import javax.annotation.Nonnull;

/**
 * Converts between {@link RemoveAssociatedDataMutation} and {@link GrpcRemoveAssociatedDataMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RemoveAssociatedDataMutationConverter extends AssociatedDataMutationConverter<RemoveAssociatedDataMutation, GrpcRemoveAssociatedDataMutation> {

	@Override
	@Nonnull
	public RemoveAssociatedDataMutation convert(@Nonnull GrpcRemoveAssociatedDataMutation mutation) {
		final AssociatedDataKey key = buildAssociatedDataKey(mutation.getAssociatedDataName(), mutation.getAssociatedDataLocale());
		return new RemoveAssociatedDataMutation(key);
	}

	@Override
	@Nonnull
	public GrpcRemoveAssociatedDataMutation convert(@Nonnull RemoveAssociatedDataMutation mutation) {
		final GrpcRemoveAssociatedDataMutation.Builder builder = GrpcRemoveAssociatedDataMutation.newBuilder()
			.setAssociatedDataName(mutation.getAssociatedDataKey().getAssociatedDataName());

		if (mutation.getAssociatedDataKey().isLocalized()) {
			builder.setAssociatedDataLocale(EvitaDataTypesConverter.toGrpcLocale(mutation.getAssociatedDataKey().getLocale()));
		}

		return builder.build();
	}
}
