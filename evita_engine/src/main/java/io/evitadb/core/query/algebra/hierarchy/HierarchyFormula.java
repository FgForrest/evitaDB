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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Hierarchy formula envelopes {@link Formula} that compute {@link io.evitadb.api.query.FilterConstraint} targeted
 * at hierarchy placement. The formula simply delegates its {@link #compute()} method to the single delegating formula.
 * Purpose of the formula is to serve as marker container that allows reconstructing the formula tree to different form.
 * This is namely used in {@link HierarchyStatisticsProducer} computation that needs to exclude constraint targeting
 * the hierarchy.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyFormula extends DeferredFormula implements NonCacheableFormula {
	private static final long CLASS_ID = -8687554987155455428L;
	@Getter
	private final Formula reducedNodeFormula;

	public HierarchyFormula(@Nonnull BitmapSupplier retrieveLambda) {
		super(retrieveLambda);
		this.reducedNodeFormula = null;
	}

	public HierarchyFormula(@Nonnull BitmapSupplier retrieveLambda, @Nullable Formula reducedNodeFormula) {
		super(retrieveLambda);
		this.reducedNodeFormula = reducedNodeFormula;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Hierarchy formula is a terminal formula and cannot have children!");
	}

	@Override
	public String toString() {
		return "HIERARCHY FILTER";
	}

	@Nullable
	public Formula getAllReducedNodeFormulaOrNull() {
		return reducedNodeFormula;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		if (reducedNodeFormula == null) {
			return hashFunction.hashLongs(
				new long[]{
					CLASS_ID,
					super.includeAdditionalHash(hashFunction)
				}
			);
		} else {
			return hashFunction.hashLongs(
				new long[]{
					CLASS_ID,
					super.includeAdditionalHash(hashFunction),
					reducedNodeFormula.computeHash(hashFunction)
				}
			);
		}
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
