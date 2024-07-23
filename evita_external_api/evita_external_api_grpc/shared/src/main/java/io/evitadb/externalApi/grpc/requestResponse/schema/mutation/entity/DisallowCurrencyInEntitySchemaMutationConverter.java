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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcDisallowCurrencyInEntitySchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import javax.annotation.Nonnull;
import java.util.Currency;

/**
 * Converts between {@link DisallowCurrencyInEntitySchemaMutation} and {@link GrpcDisallowCurrencyInEntitySchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DisallowCurrencyInEntitySchemaMutationConverter implements SchemaMutationConverter<DisallowCurrencyInEntitySchemaMutation, GrpcDisallowCurrencyInEntitySchemaMutation> {

	@Nonnull
	public DisallowCurrencyInEntitySchemaMutation convert(@Nonnull GrpcDisallowCurrencyInEntitySchemaMutation mutation) {
		return new DisallowCurrencyInEntitySchemaMutation(
			mutation.getCurrenciesList()
				.stream()
				.map(EvitaDataTypesConverter::toCurrency)
				.toArray(Currency[]::new)
		);
	}

	@Nonnull
	public GrpcDisallowCurrencyInEntitySchemaMutation convert(@Nonnull DisallowCurrencyInEntitySchemaMutation mutation) {
		return GrpcDisallowCurrencyInEntitySchemaMutation.newBuilder()
			.addAllCurrencies(
				mutation.getCurrencies()
					.stream()
					.map(EvitaDataTypesConverter::toGrpcCurrency)
					.toList()
			)
			.build();
	}
}
