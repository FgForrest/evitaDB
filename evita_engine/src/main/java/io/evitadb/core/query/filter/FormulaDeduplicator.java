/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.query.filter;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * This class is responsible for deduplicating formulas to optimize calculation performance by reusing memoized results.
 * In other words, existing instance can be reused on multiple places.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class FormulaDeduplicator extends FormulaCloner implements FormulaPostProcessor {
	/**
	 * Reference to the original unchanged {@link Formula}.
	 */
	private Formula originalFormula;
	/**
	 * Flag indicating that at least one formula was deduplicated.
	 */
	private boolean deduplicationHappened = false;

	public FormulaDeduplicator(@Nonnull Formula originalFormula) {
		super(new Deduplicator());
		this.originalFormula = originalFormula;
	}

	@Nonnull
	@Override
	public Formula getPostProcessedFormula() {
		final Formula result;
		if (this.deduplicationHappened) {
			result = getResultClone();
		} else {
			result = this.originalFormula;
		}
		((Deduplicator)this.mutator).clear();
		this.deduplicationHappened = false;
		return result;
	}

	/**
	 * This implementation of the {@link BiFunction} interface detects whether particular formula was already observed
	 * in the formula tree and can be thus deduplicated - i.e. existing instance can be reused on multiple places.
	 * This deduplication optimizes the final calculation performance by allowing to reuse the memoized result of
	 * the formula on different places of the tree.
	 *
	 * Two formulas are the same only when both their hash and their transactional id hash match: the hash describes
	 * the computation, the transactional ids the data it reads. Structurally equal formulas over different indexes -
	 * the hierarchy nodes from the roots of the live and of the archived tree - share the hash but read different
	 * data, and replacing one with the other would silently drop what the other selects.
	 */
	private static class Deduplicator implements BiFunction<FormulaCloner, Formula, Formula> {
		/**
		 * Cache of formulas.
		 */
		private final Map<FormulaIdentity, Formula> formulaCache = CollectionUtils.createHashMap(64);

		@Override
		public Formula apply(FormulaCloner formulaCloner, Formula formula) {
			final FormulaDeduplicator clonerInstance = (FormulaDeduplicator) formulaCloner;
			if (clonerInstance.originalFormula == null) {
				clonerInstance.originalFormula = formula;
			}
			final Formula existingFormula = this.formulaCache.putIfAbsent(
				new FormulaIdentity(formula.getHash(), formula.getTransactionalIdHash()), formula
			);
			if (existingFormula != null) {
				clonerInstance.deduplicationHappened = true;
				return existingFormula;
			} else {
				// include the original formula
				return formula;
			}
		}

		/**
		 * Clears the formula cache.
		 */
		void clear() {
			this.formulaCache.clear();
		}

		/**
		 * Identity of a formula for deduplication purposes.
		 *
		 * @param hash                hash of the computation, see {@link Formula#getHash()}
		 * @param transactionalIdHash hash of the data the computation reads, see
		 *                            {@link Formula#getTransactionalIdHash()}
		 */
		private record FormulaIdentity(long hash, long transactionalIdHash) {
		}

	}
}
