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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcDisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link DisallowEvolutionModeInCatalogSchemaMutation} and {@link GrpcDisallowEvolutionModeInCatalogSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DisallowEvolutionModeInCatalogSchemaMutationConverter implements SchemaMutationConverter<DisallowEvolutionModeInCatalogSchemaMutation, GrpcDisallowEvolutionModeInCatalogSchemaMutation> {
	public static final DisallowEvolutionModeInCatalogSchemaMutationConverter INSTANCE = new DisallowEvolutionModeInCatalogSchemaMutationConverter();

	@Nonnull
	public DisallowEvolutionModeInCatalogSchemaMutation convert(@Nonnull GrpcDisallowEvolutionModeInCatalogSchemaMutation mutation) {
		return new DisallowEvolutionModeInCatalogSchemaMutation(
			mutation.getEvolutionModesList()
				.stream()
				.map(EvitaEnumConverter::toCatalogEvolutionMode)
				.toArray(CatalogEvolutionMode[]::new)
		);
	}

	@Nonnull
	public GrpcDisallowEvolutionModeInCatalogSchemaMutation convert(@Nonnull DisallowEvolutionModeInCatalogSchemaMutation mutation) {
		return GrpcDisallowEvolutionModeInCatalogSchemaMutation.newBuilder()
			.addAllEvolutionModes(
				mutation.getEvolutionModes()
					.stream()
					.map(EvitaEnumConverter::toGrpcCatalogEvolutionMode)
					.toList()
			)
			.build();
	}
}
