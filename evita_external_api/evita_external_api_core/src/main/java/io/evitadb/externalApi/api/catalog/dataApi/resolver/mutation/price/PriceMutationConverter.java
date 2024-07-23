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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.PriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Output;

import javax.annotation.Nonnull;

/**
 * Ancestor abstract implementation for {@link PriceMutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
abstract class PriceMutationConverter<M extends PriceMutation> extends LocalMutationConverter<M> {

	protected PriceMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                 @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	protected PriceKey resolvePriceKey(@Nonnull Input input) {
		return new PriceKey(
			input.getProperty(PriceMutationDescriptor.PRICE_ID),
			input.getProperty(PriceMutationDescriptor.PRICE_LIST),
			input.getProperty(PriceMutationDescriptor.CURRENCY)
		);
	}

	@Override
	protected void convertToOutput(@Nonnull M mutation, @Nonnull Output output) {
		output.setProperty(PriceMutationDescriptor.PRICE_ID, mutation.getPriceKey().priceId());
		output.setProperty(PriceMutationDescriptor.PRICE_LIST, mutation.getPriceKey().priceList());
		output.setProperty(PriceMutationDescriptor.CURRENCY, mutation.getPriceKey().currency());
	}
}
