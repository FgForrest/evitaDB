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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.hierarchy;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Hierarchy formula envelopes {@link Formula} that compute {@link io.evitadb.api.query.FilterConstraint} targeted
 * at hierarchy placement. The formula simply delegates its {@link #compute()} method to the single delegating formula.
 * Purpose of the formula is to serve as marker container that allows reconstructing the formula tree to different form.
 * This is namely used in {@link HierarchyStatisticsProducer} computation that needs to exclude constraint targeting
 * the hierarchy.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyFormula extends AbstractFormula implements NonCacheableFormula {
	private static final long CLASS_ID = -8687554987155455428L;
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";

	public HierarchyFormula(@Nonnull Formula innerFormula) {
		super(innerFormula);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new HierarchyFormula(innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public int getEstimatedCardinality() {
		return innerFormulas[0].getEstimatedCardinality();
	}

	@Override
	public String toString() {
		return "HIERARCHY FILTER";
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		if (innerFormulas.length == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0].compute();
		} else {
			throw new EvitaInternalError(ERROR_SINGLE_FORMULA_EXPECTED);
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
