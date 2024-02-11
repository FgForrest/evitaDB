/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.filter;

import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.utils.CollectionUtils;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class FormulaDeduplicator extends FormulaCloner implements FormulaPostProcessor {
	/**
	 * Hash function used to compute hash of formulas.
	 */
	private static final LongHashFunction HASH_FUNCTION = CacheSupervisor.createHashFunction();
	/**
	 * Reference to the original unchanged {@link Formula}.
	 */
	private Formula originalFormula;
	/**
	 * Flag indicating that at least one formula was deduplicated.
	 */
	private boolean deduplicationHappened = false;

	/* TODO JNO - tady spočítat celkovou složitost root formuly, protože se musí započítat deduplikace */
	public FormulaDeduplicator(@Nonnull Formula originalFormula) {
		super(new Deduplicator());
		this.originalFormula = originalFormula;
	}

	@Nonnull
	@Override
	public Formula getPostProcessedFormula() {
		final Formula result = this.deduplicationHappened ?
			getResultClone() : this.originalFormula;
		((Deduplicator)this.mutator).clear();
		this.deduplicationHappened = false;
		this.originalFormula = null;
		return result;
	}

	private static class Deduplicator implements BiFunction<FormulaCloner, Formula, Formula> {
		/**
		 * Cache of formulas.
		 */
		private final Map<Long, Formula> formulaCache = CollectionUtils.createHashMap(64);

		@Override
		public Formula apply(FormulaCloner formulaCloner, Formula formula) {
			final FormulaDeduplicator clonerInstance = (FormulaDeduplicator) formulaCloner;
			if (clonerInstance.originalFormula == null) {
				clonerInstance.originalFormula = formula;
			}
			final Formula existingFormula = formulaCache.putIfAbsent(formula.computeHash(HASH_FUNCTION), formula);
			if (existingFormula != null) {
				clonerInstance.deduplicationHappened = true;
				return existingFormula;
			} else {
				// include the original formula
				return formula;
			}
		}

		void clear() {
			this.formulaCache.clear();
		}

	}
}
