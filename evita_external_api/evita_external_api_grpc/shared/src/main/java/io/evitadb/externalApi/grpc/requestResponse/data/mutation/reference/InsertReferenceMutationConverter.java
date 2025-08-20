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

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.externalApi.grpc.generated.GrpcInsertReferenceMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link InsertReferenceMutation} and {@link GrpcInsertReferenceMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InsertReferenceMutationConverter implements LocalMutationConverter<InsertReferenceMutation, GrpcInsertReferenceMutation> {
	public static final InsertReferenceMutationConverter INSTANCE = new InsertReferenceMutationConverter();

	@Override
	@Nonnull
	public InsertReferenceMutation convert(@Nonnull GrpcInsertReferenceMutation mutation) {
		return new InsertReferenceMutation(
			new ReferenceKey(
				mutation.getReferenceName(),
				mutation.getReferencePrimaryKey()
			),
			EvitaEnumConverter.toCardinality(mutation.getReferenceCardinality()).orElse(null),
			mutation.hasReferencedEntityType() ? mutation.getReferencedEntityType().getValue() : null
		);
	}

	@Nonnull
	@Override
	public GrpcInsertReferenceMutation convert(@Nonnull InsertReferenceMutation mutation) {
		final GrpcInsertReferenceMutation.Builder builder = GrpcInsertReferenceMutation.newBuilder()
			.setReferenceName(mutation.getReferenceKey().referenceName())
			.setReferencePrimaryKey(mutation.getReferenceKey().primaryKey())
			.setReferenceCardinality(EvitaEnumConverter.toGrpcCardinality(mutation.getReferenceCardinality()));

		if (mutation.getReferencedEntityType() != null) {
			builder.setReferencedEntityType(StringValue.of(mutation.getReferencedEntityType()));
		}

		return builder.build();
	}
}
