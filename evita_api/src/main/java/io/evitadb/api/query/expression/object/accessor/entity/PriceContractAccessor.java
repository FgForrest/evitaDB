/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression.object.accessor.entity;

import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Implementation of {@link ObjectPropertyAccessor} for {@link PriceContract} objects.
 * Enables dot-property access expressions in EvitaEL to navigate the price structure,
 * e.g. `price.priceWithTax`, `price.currency`, `price.validity`.
 *
 * @see ObjectPropertyAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class PriceContractAccessor implements ObjectPropertyAccessor {

	public static final String PRICE_ID_PROPERTY = "priceId";
	public static final String PRICE_LIST_PROPERTY = "priceList";
	public static final String CURRENCY_PROPERTY = "currency";
	public static final String CURRENCY_CODE_PROPERTY = "currencyCode";
	public static final String INNER_RECORD_ID_PROPERTY = "innerRecordId";
	public static final String PRICE_WITHOUT_TAX_PROPERTY = "priceWithoutTax";
	public static final String TAX_RATE_PROPERTY = "taxRate";
	public static final String PRICE_WITH_TAX_PROPERTY = "priceWithTax";
	public static final String VALIDITY_PROPERTY = "validity";
	public static final String INDEXED_PROPERTY = "indexed";
	public static final String VERSION_PROPERTY = "version";
	public static final String DROPPED_PROPERTY = "dropped";

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { PriceContract.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String propertyIdentifier
	) throws ExpressionEvaluationException {
		if (!(object instanceof PriceContract price)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected PriceContract.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case PRICE_ID_PROPERTY -> price.priceId();
			case PRICE_LIST_PROPERTY -> price.priceList();
			case CURRENCY_PROPERTY -> price.currency().getCurrencyCode();
			case CURRENCY_CODE_PROPERTY -> price.currencyCode();
			case INNER_RECORD_ID_PROPERTY -> price.innerRecordId();
			case PRICE_WITHOUT_TAX_PROPERTY -> price.priceWithoutTax();
			case TAX_RATE_PROPERTY -> price.taxRate();
			case PRICE_WITH_TAX_PROPERTY -> price.priceWithTax();
			case VALIDITY_PROPERTY -> price.validity();
			case INDEXED_PROPERTY -> price.indexed();
			case VERSION_PROPERTY -> price.version();
			case DROPPED_PROPERTY -> price.dropped();
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on PriceContract.",
					"Property `" + propertyIdentifier + "` does not exist on price."
				);
		};
	}
}
