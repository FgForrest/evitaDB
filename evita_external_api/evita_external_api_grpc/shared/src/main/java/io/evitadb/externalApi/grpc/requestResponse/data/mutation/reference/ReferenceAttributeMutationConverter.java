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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutation;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingAttributeMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ReferenceAttributeMutation} and {@link GrpcReferenceAttributeMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceAttributeMutationConverter implements LocalMutationConverter<ReferenceAttributeMutation, GrpcReferenceAttributeMutation> {
	public static final ReferenceAttributeMutationConverter INSTANCE = new ReferenceAttributeMutationConverter();

	@Override
	@Nonnull
	public ReferenceAttributeMutation convert(@Nonnull GrpcReferenceAttributeMutation mutation) {
		return new ReferenceAttributeMutation(
			new ReferenceKey(
				mutation.getReferenceName(),
				mutation.getReferencePrimaryKey(),
				mutation.getInternalPrimaryKey()
			),
			DelegatingAttributeMutationConverter.INSTANCE.convert(mutation.getAttributeMutation())
		);
	}

	@Nonnull
	@Override
	public GrpcReferenceAttributeMutation convert(@Nonnull ReferenceAttributeMutation mutation) {
		return GrpcReferenceAttributeMutation
			.newBuilder()
			.setReferenceName(mutation.getReferenceKey().referenceName())
			.setReferencePrimaryKey(mutation.getReferenceKey().primaryKey())
			.setInternalPrimaryKey(mutation.getReferenceKey().internalPrimaryKey())
			.setAttributeMutation(DelegatingAttributeMutationConverter.INSTANCE.convert(mutation.getAttributeMutation()))
			.build();
	}
}
