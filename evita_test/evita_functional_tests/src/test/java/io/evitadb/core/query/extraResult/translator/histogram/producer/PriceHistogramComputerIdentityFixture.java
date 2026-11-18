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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityFixture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Map;
import java.util.Set;


/**
 * Real construction inputs for {@link PriceHistogramComputer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceHistogramComputerIdentityFixture implements IdentityFixture<PriceHistogramComputer> {
	/** Covers relaxed, unchanged and empty baselines and a captured sorter lookup. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<PriceHistogramComputer>> modes() {
		final Formula first = AttributeHistogramComputerIdentityFixture.leaf(1);
		final Formula second = AttributeHistogramComputerIdentityFixture.leaf(2);
		final Formula third = AttributeHistogramComputerIdentityFixture.leaf(3);
		final Formula opaque = AttributeHistogramComputerIdentityFixture.opaqueLeaf();
		final FilteredPriceRecordsLookupResult lookup = new FilteredPriceRecordsLookupResult(new PriceRecordContract[0]);
		return Map.of("request", new ConstructionMode<>(
			() -> create(10, HistogramBehavior.STANDARD, 2, QueryPriceMode.WITH_TAX, first, second, null),
			Map.of(
				"bucketCount", () -> create(11, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, first, second, null),
				"behavior", () -> create(10, HistogramBehavior.OPTIMIZED, 2,
					QueryPriceMode.WITH_TAX, first, second, null),
				"indexedPricePlaces", () -> create(10, HistogramBehavior.STANDARD, 3,
					QueryPriceMode.WITH_TAX, first, second, null),
				"queryPriceMode", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITHOUT_TAX, first, second, null),
				"filteringFormula", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, third, second, null),
				"relaxedBaseline", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, first, third, null),
				"empty baseline", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, first, EmptyFormula.INSTANCE, null),
				"unchanged baseline", () -> {
					final Formula filter = first;
					return create(10, HistogramBehavior.STANDARD, 2, QueryPriceMode.WITH_TAX, filter, filter, null);
				}
			),
			Map.of(
				"priceRecordsLookupResult", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, first, second, lookup),
				"filteringFormula", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, opaque, second, null),
				"relaxedBaseline", () -> create(10, HistogramBehavior.STANDARD, 2,
					QueryPriceMode.WITH_TAX, first, opaque, null)
			), Map.of(), Map.of(), Set.of(), Set.of(), Set.of()
		));
	}

	/** Builds a computer with explicit filtering and baseline provenance. */
	@Nonnull
	static PriceHistogramComputer create(
		int buckets, @Nonnull HistogramBehavior behavior, int places, @Nonnull QueryPriceMode mode,
		@Nonnull Formula filter, @Nonnull Formula baseline, @Nullable FilteredPriceRecordsLookupResult lookup
	) {
		return new PriceHistogramComputer(buckets, behavior, places, mode, filter, baseline, lookup);
	}
}
