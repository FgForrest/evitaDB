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

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Currency;

/**
 * This mutation allows to remove existing {@link Price} of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemovePriceMutation extends PriceMutation {
	@Serial private static final long serialVersionUID = -1049985270997762455L;

	public RemovePriceMutation(@Nonnull PriceKey priceKey) {
		super(priceKey);
	}

	private RemovePriceMutation(@Nonnull PriceKey priceKey, long decisiveTimestamp) {
		super(priceKey, decisiveTimestamp);
	}

	public RemovePriceMutation(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		this(new PriceKey(priceId, priceList, currency));
	}

	@Nonnull
	@Override
	public PriceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable PriceContract existingValue) {
		Assert.isTrue(
				existingValue != null && existingValue.exists(),
				() -> new InvalidMutationException("Cannot remove price that doesn't exist!")
		);
		return new Price(
			existingValue.version() + 1,
			existingValue.priceKey(),
			existingValue.innerRecordId(),
			existingValue.priceWithoutTax(),
			existingValue.taxRate(),
			existingValue.priceWithTax(),
			existingValue.validity(),
			existingValue.indexed(),
			true
		);
	}

	@Override
	public long getPriority() {
		return PRIORITY_REMOVAL;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new RemovePriceMutation(this.priceKey, newDecisiveTimestamp);
	}

	@Override
	public String toString() {
		return "remove price: `" + this.priceKey + "`";
	}

}
