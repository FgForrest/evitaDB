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

package io.evitadb.api.requestResponse.data.key;

import io.evitadb.api.requestResponse.data.structure.Price;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Currency;

/**
 * This price key contains information that are massively shared among prices and thus deserve compress in the serialized
 * form.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CompressiblePriceKey extends AbstractPriceKey implements Comparable<CompressiblePriceKey> {
	@Serial private static final long serialVersionUID = -1937681739908967584L;

	public CompressiblePriceKey(@Nonnull Price.PriceKey priceKey) {
		super(priceKey);
	}

	public CompressiblePriceKey(@Nonnull String priceList, @Nonnull Currency currency) {
		super(priceList, currency);
	}

	@Override
	public int compareTo(CompressiblePriceKey o) {
		int result = this.currency.getCurrencyCode().compareTo(o.currency.getCurrencyCode());
		if (result == 0) {
			return this.priceList.compareTo(o.priceList);
		} else {
			return result;
		}
	}

}
