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

package io.evitadb.core.query.algebra.price;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.test.identity.*;
import javax.annotation.Nonnull;

import java.math.BigDecimal;
import java.util.Currency;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real immutable inputs and versioned indexes used by price identity fixtures.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PriceIdentityFixtureSupport {
	/** Prevents construction of the fixture helper. */
	private PriceIdentityFixtureSupport() { }

	/** Returns a fresh exact formula child. */
	@Nonnull
	public static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/** Returns an immutable evaluation context with the requested validity input. */
	@Nonnull
	public static PriceEvaluationContext context(long validity) {
		return new PriceEvaluationContext(validity);
	}

	/** Returns a real versioned price index, whose id distinguishes its entire lifetime. */
	@Nonnull
	public static PriceListAndCurrencyPriceSuperIndex index() {
		return new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
		);
	}

	/** Returns a range predicate differing from the unrestricted predicate in every value component. */
	@Nonnull
	public static PricePredicate.PriceRecordPredicate predicate() {
		return new PricePredicate.PriceRecordPredicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2);
	}
}
