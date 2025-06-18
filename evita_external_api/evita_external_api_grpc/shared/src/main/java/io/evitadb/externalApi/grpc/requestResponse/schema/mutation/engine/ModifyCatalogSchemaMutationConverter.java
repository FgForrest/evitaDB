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

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyCatalogSchemaMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link ModifyCatalogSchemaMutation} and {@link GrpcModifyCatalogSchemaMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 202
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyCatalogSchemaMutationConverter
	implements SchemaMutationConverter<ModifyCatalogSchemaMutation, GrpcModifyCatalogSchemaMutation> {
	public static final ModifyCatalogSchemaMutationConverter INSTANCE = new ModifyCatalogSchemaMutationConverter();

	@Nonnull
	public GrpcModifyCatalogSchemaMutation convert(@Nonnull ModifyCatalogSchemaMutation mutation) {
		final Builder builder = GrpcModifyCatalogSchemaMutation
			.newBuilder()
			.setCatalogName(mutation.getCatalogName());
		for (LocalCatalogSchemaMutation schemaMutation : mutation.getSchemaMutations()) {
			builder.addSchemaMutations(
				DelegatingLocalCatalogSchemaMutationConverter.INSTANCE.convert(schemaMutation)
			);
		}
		return builder
			.build();
	}

	@Nonnull
	public ModifyCatalogSchemaMutation convert(@Nonnull GrpcModifyCatalogSchemaMutation mutation) {
		return new ModifyCatalogSchemaMutation(
			mutation.getCatalogName(),
			null,
			mutation.getSchemaMutationsList()
			        .stream()
			        .map(DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
			        .toArray(LocalCatalogSchemaMutation[]::new)
		);
	}
}
