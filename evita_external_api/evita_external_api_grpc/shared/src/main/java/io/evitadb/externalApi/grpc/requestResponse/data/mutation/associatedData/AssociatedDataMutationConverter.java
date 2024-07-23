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

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcLocale;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Ancestor for all converters converting implementations of {@link AssociatedDataMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AssociatedDataMutationConverter<J extends AssociatedDataMutation, G extends Message> implements LocalMutationConverter<J, G> {

	@Nonnull
	protected static AssociatedDataContract.AssociatedDataKey buildAssociatedDataKey(@Nonnull String associatedDataName,
	                                                                                 @Nonnull GrpcLocale associatedDataLocale) {
		if (!associatedDataLocale.getDefaultInstanceForType().equals(associatedDataLocale)) {
			return new AssociatedDataKey(
				associatedDataName,
				EvitaDataTypesConverter.toLocale(associatedDataLocale)
			);
		} else {
			return new AssociatedDataKey(associatedDataName);
		}
	}
}
