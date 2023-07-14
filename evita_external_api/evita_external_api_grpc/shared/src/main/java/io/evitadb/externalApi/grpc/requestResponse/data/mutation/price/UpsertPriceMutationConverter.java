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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.price;

import com.google.protobuf.Int32Value;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcUpsertPriceMutation;

import javax.annotation.Nonnull;

/**
 * Converts between {@link UpsertPriceMutation} and {@link GrpcUpsertPriceMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class UpsertPriceMutationConverter extends PriceMutationConverter<UpsertPriceMutation, GrpcUpsertPriceMutation> {

	@Override
	@Nonnull
	public UpsertPriceMutation convert(@Nonnull GrpcUpsertPriceMutation mutation) {
		if (!mutation.hasPriceWithoutTax() || !mutation.hasTaxRate() || !mutation.hasPriceWithTax()) {
			throw new EvitaInvalidUsageException("Price mutation must have priceWithoutTax, taxRate and priceWithTax.");
		}
		return new UpsertPriceMutation(
			buildPriceKey(mutation.getPriceId(), mutation.getPriceList(), mutation.getCurrency()),
			mutation.hasInnerRecordId() ? mutation.getInnerRecordId().getValue() : null,
			EvitaDataTypesConverter.toBigDecimal(mutation.getPriceWithoutTax()),
			EvitaDataTypesConverter.toBigDecimal(mutation.getTaxRate()),
			EvitaDataTypesConverter.toBigDecimal(mutation.getPriceWithTax()),
			mutation.hasValidity() ? EvitaDataTypesConverter.toDateTimeRange(mutation.getValidity()) : null,
			mutation.getSellable()
		);
	}

	@Nonnull
	@Override
	public GrpcUpsertPriceMutation convert(@Nonnull UpsertPriceMutation mutation) {
		final GrpcUpsertPriceMutation.Builder builder = GrpcUpsertPriceMutation.newBuilder()
			.setPriceId(mutation.getPriceKey().priceId())
			.setPriceList(mutation.getPriceKey().priceList())
			.setCurrency(EvitaDataTypesConverter.toGrpcCurrency(mutation.getPriceKey().currency()))
			.setPriceWithoutTax(EvitaDataTypesConverter.toGrpcBigDecimal(mutation.getPriceWithoutTax()))
			.setTaxRate(EvitaDataTypesConverter.toGrpcBigDecimal(mutation.getTaxRate()))
			.setPriceWithTax(EvitaDataTypesConverter.toGrpcBigDecimal(mutation.getPriceWithTax()))
			.setSellable(mutation.isSellable());

		if (mutation.getInnerRecordId() != null) {
			builder.setInnerRecordId(Int32Value.of(mutation.getInnerRecordId()));
		}
		if (mutation.getValidity() != null) {
			builder.setValidity(EvitaDataTypesConverter.toGrpcDateTimeRange(mutation.getValidity()));
		}

		return builder.build();
	}
}
