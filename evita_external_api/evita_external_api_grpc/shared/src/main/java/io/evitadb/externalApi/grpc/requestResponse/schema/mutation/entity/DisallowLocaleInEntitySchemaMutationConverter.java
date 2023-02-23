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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowLocaleInEntitySchemaMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcDisallowLocaleInEntitySchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Converts between {@link DisallowLocaleInEntitySchemaMutation} and {@link GrpcDisallowLocaleInEntitySchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DisallowLocaleInEntitySchemaMutationConverter implements SchemaMutationConverter<DisallowLocaleInEntitySchemaMutation, GrpcDisallowLocaleInEntitySchemaMutation> {

	@Nonnull
	public DisallowLocaleInEntitySchemaMutation convert(@Nonnull GrpcDisallowLocaleInEntitySchemaMutation mutation) {
		return new DisallowLocaleInEntitySchemaMutation(
			mutation.getLocalesList()
				.stream()
				.map(EvitaDataTypesConverter::toLocale)
				.toArray(Locale[]::new)
		);
	}

	@Nonnull
	public GrpcDisallowLocaleInEntitySchemaMutation convert(@Nonnull DisallowLocaleInEntitySchemaMutation mutation) {
		return GrpcDisallowLocaleInEntitySchemaMutation.newBuilder()
			.addAllLocales(
				mutation.getLocales()
					.stream()
					.map(EvitaDataTypesConverter::toGrpcLocale)
					.toList()
			)
			.build();
	}
}
