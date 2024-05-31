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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcPriceInnerRecordHandling;
import io.evitadb.externalApi.grpc.generated.GrpcSetPriceInnerRecordHandlingMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link SetPriceInnerRecordHandlingMutation} and {@link GrpcSetPriceInnerRecordHandlingMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SetPriceInnerRecordHandlingMutationConverter implements LocalMutationConverter<SetPriceInnerRecordHandlingMutation, GrpcSetPriceInnerRecordHandlingMutation> {

	@Override
	@Nonnull
	public SetPriceInnerRecordHandlingMutation convert(@Nonnull GrpcSetPriceInnerRecordHandlingMutation mutation) {
		if (mutation.getPriceInnerRecordHandling() == GrpcPriceInnerRecordHandling.UNRECOGNIZED) {
			throw new EvitaInvalidUsageException("Unrecognized price inner record handling: " + mutation.getPriceInnerRecordHandling());
		}
		return new SetPriceInnerRecordHandlingMutation(
			EvitaEnumConverter.toPriceInnerRecordHandling(mutation.getPriceInnerRecordHandling())
		);
	}

	@Nonnull
	@Override
	public GrpcSetPriceInnerRecordHandlingMutation convert(@Nonnull SetPriceInnerRecordHandlingMutation mutation) {
		return GrpcSetPriceInnerRecordHandlingMutation.newBuilder()
			.setPriceInnerRecordHandling(EvitaEnumConverter.toGrpcPriceInnerRecordHandling(mutation.getPriceInnerRecordHandling()))
			.build();
	}
}
