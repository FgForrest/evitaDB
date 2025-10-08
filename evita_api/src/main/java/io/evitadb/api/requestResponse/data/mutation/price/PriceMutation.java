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

package io.evitadb.api.requestResponse.data.mutation.price;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.ContainerType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Base mutation class for mutations that work with {@link Price} of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(exclude = "decisiveTimestamp")
public abstract class PriceMutation implements LocalMutation<PriceContract, PriceKey> {
	@Serial private static final long serialVersionUID = 2424285135744614172L;
	@Getter private final long decisiveTimestamp;
	/**
	 * Identification of the price that the mutation affects.
	 */
	@Nonnull
	@Getter protected final PriceKey priceKey;

	protected PriceMutation(@Nonnull PriceKey priceKey) {
		this.priceKey = priceKey;
		this.decisiveTimestamp = System.nanoTime();
	}

	protected PriceMutation(@Nonnull PriceKey priceKey, long decisiveTimestamp) {
		this.priceKey = priceKey;
		this.decisiveTimestamp = decisiveTimestamp;
	}

	@Nonnull
	@Override
	public PriceKey getComparableKey() {
		return this.priceKey;
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.PRICE;
	}

}
