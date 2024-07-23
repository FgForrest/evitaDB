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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.price;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCurrency;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Ancestor for all converters converting implementations of {@link PriceMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PriceMutationConverter<J extends PriceMutation, G extends Message> implements LocalMutationConverter<J, G> {

	/**
	 * This method extracts and builds price key from passed {@code priceId}, {@code priceList} and {@link GrpcCurrency} to Evita {@link PriceKey}.
	 * All of specified parameters must be set to be able to construct new price key.
	 *
	 * @param priceId   of price to be built
	 * @param priceList id of price to be built
	 * @param currency  of price to be built
	 * @return built price key
	 */
	@Nonnull
	protected static PriceKey buildPriceKey(int priceId, @Nonnull String priceList, @Nonnull GrpcCurrency currency) {
		if (currency.getDefaultInstanceForType().equals(currency)) {
			throw new EvitaInvalidUsageException("Currency is required!");
		}
		return new PriceKey(
			priceId,
			priceList,
			EvitaDataTypesConverter.toCurrency(currency)
		);
	}
}
