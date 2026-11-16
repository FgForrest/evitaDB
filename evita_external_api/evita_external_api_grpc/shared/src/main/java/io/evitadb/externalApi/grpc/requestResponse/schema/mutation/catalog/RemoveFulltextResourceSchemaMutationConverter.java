/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextResourceSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveFulltextResourceSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/** Converts the mutation that removes a catalog fulltext resource between its Java and gRPC forms. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RemoveFulltextResourceSchemaMutationConverter
	implements SchemaMutationConverter<
		RemoveFulltextResourceSchemaMutation, GrpcRemoveFulltextResourceSchemaMutation> {
	/** Shared stateless converter. */
	@Nonnull
	public static final RemoveFulltextResourceSchemaMutationConverter INSTANCE =
		new RemoveFulltextResourceSchemaMutationConverter();

	@Nonnull
	@Override
	public RemoveFulltextResourceSchemaMutation convert(@Nonnull GrpcRemoveFulltextResourceSchemaMutation mutation) {
		return new RemoveFulltextResourceSchemaMutation(mutation.getName());
	}

	@Nonnull
	@Override
	public GrpcRemoveFulltextResourceSchemaMutation convert(@Nonnull RemoveFulltextResourceSchemaMutation mutation) {
		return GrpcRemoveFulltextResourceSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.build();
	}
}
