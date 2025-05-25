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

package io.evitadb.core.query.extraResult.translator.histogram.cache;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import lombok.EqualsAndHashCode;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * This DTO is the {@link Histogram} for price related data. This specific class is created so that the price histogram
 * can be easily extracted from the result by calling:
 *
 * ```
 * final PriceHistogram priceHistogram = result.getExtraResult(PriceHistogram.class);
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see Histogram for details
 */
@EqualsAndHashCode
@ThreadSafe
public class CacheablePriceHistogram implements CacheableHistogramContract, EvitaResponseExtraResult {
	@Serial private static final long serialVersionUID = -8335705666656262074L;
	@Delegate private final CacheableHistogramContract histogram;

	public CacheablePriceHistogram(@Nonnull CacheableHistogramContract histogram) {
		this.histogram = histogram;
	}

	@Override
	public String toString() {
		return this.histogram.toString();
	}
}
