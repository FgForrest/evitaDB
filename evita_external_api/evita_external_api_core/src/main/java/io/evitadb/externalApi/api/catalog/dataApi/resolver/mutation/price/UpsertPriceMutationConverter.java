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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.LocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.Input;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link LocalMutationConverter} for resolving {@link UpsertPriceMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class UpsertPriceMutationConverter extends PriceMutationConverter<UpsertPriceMutation> {

	public UpsertPriceMutationConverter(@Nonnull MutationObjectParser objectParser,
	                                    @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectParser, exceptionFactory);
	}

	@Nonnull
	@Override
	protected String getMutationName() {
		return UpsertPriceMutationDescriptor.THIS.name();
	}

	@Nonnull
	@Override
	protected UpsertPriceMutation convert(@Nonnull Input input) {
		return new UpsertPriceMutation(
			resolvePriceKey(input),
			input.getOptionalField(UpsertPriceMutationDescriptor.INNER_RECORD_ID),
			input.getRequiredField(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX),
			input.getRequiredField(UpsertPriceMutationDescriptor.TAX_RATE),
			input.getRequiredField(UpsertPriceMutationDescriptor.PRICE_WITH_TAX),
			input.getOptionalField(UpsertPriceMutationDescriptor.VALIDITY),
			input.getRequiredField(UpsertPriceMutationDescriptor.SELLABLE)
		);
	}
}
