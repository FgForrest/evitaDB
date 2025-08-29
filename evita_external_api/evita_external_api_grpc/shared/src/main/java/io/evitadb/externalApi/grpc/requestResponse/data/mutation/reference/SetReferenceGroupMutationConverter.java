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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceGroupMutation;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link SetReferenceGroupMutation} and {@link GrpcSetReferenceGroupMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceGroupMutationConverter implements LocalMutationConverter<SetReferenceGroupMutation, GrpcSetReferenceGroupMutation> {
	public static final SetReferenceGroupMutationConverter INSTANCE = new SetReferenceGroupMutationConverter();

	@Override
	@Nonnull
	public SetReferenceGroupMutation convert(@Nonnull GrpcSetReferenceGroupMutation mutation) {
		return new SetReferenceGroupMutation(
			new ReferenceKey(
				mutation.getReferenceName(),
				mutation.getReferencePrimaryKey(),
				mutation.getInternalPrimaryKey()
			),
			mutation.hasGroupType() ? mutation.getGroupType().getValue() : null,
			mutation.getGroupPrimaryKey()
		);
	}

	@Nonnull
	@Override
	public GrpcSetReferenceGroupMutation convert(@Nonnull SetReferenceGroupMutation mutation) {
		final GrpcSetReferenceGroupMutation.Builder builder = GrpcSetReferenceGroupMutation
			.newBuilder()
			.setReferenceName(mutation.getComparableKey().referenceName())
			.setReferencePrimaryKey(mutation.getComparableKey().primaryKey())
			.setInternalPrimaryKey(mutation.getReferenceKey().internalPrimaryKey())
			.setGroupPrimaryKey(mutation.getGroupPrimaryKey());

		if (mutation.getGroupType() != null) {
			builder.setGroupType(StringValue.of(mutation.getGroupType()));
		}

		return builder.build();
	}
}
