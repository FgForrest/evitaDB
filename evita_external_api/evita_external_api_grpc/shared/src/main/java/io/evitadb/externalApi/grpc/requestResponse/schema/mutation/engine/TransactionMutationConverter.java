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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine;

import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.grpc.generated.GrpcTransactionMutation;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.MutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;

/**
 * Converts between {@link TransactionMutation} and {@link GrpcTransactionMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TransactionMutationConverter implements MutationConverter<TransactionMutation, GrpcTransactionMutation> {
	public static final TransactionMutationConverter INSTANCE = new TransactionMutationConverter();

	@Nonnull
	public TransactionMutation convert(@Nonnull GrpcTransactionMutation mutation) {
		return new TransactionMutation(
			toUuid(mutation.getTransactionId()),
			mutation.getVersion(),
			mutation.getMutationCount(),
			mutation.getWalSizeInBytes(),
			toOffsetDateTime(mutation.getCommitTimestamp())
		);
	}

	@Nonnull
	public GrpcTransactionMutation convert(@Nonnull TransactionMutation mutation) {
		return GrpcTransactionMutation.newBuilder()
			.setTransactionId(toGrpcUuid(mutation.getTransactionId()))
			.setVersion(mutation.getVersion())
			.setMutationCount(mutation.getMutationCount())
			.setWalSizeInBytes(mutation.getWalSizeInBytes())
			.setCommitTimestamp(toGrpcOffsetDateTime(mutation.getCommitTimestamp()))
			.build();
	}

}
