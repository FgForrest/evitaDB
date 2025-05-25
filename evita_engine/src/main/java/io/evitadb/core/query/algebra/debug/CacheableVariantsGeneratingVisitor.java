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

package io.evitadb.core.query.algebra.debug;

import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.NonCacheableFormulaScope;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The visitor applies all possible variants of the original formula where cacheable parts are one by one transformed
 * to the {@link CachePayloadHeader} counterparts. The visitor is used for debugging purposes to verify that
 * the {@link QueryPlan} for all of them produce exactly same results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CacheableVariantsGeneratingVisitor implements FormulaVisitor {
	/**
	 * Contains replacement counterparts for each cacheable formula in original tree.
	 */
	private final IdentityHashMap<Formula, CachePayloadHeader> replacements = new IdentityHashMap<>();
	/**
	 * Contains the original formula that is examined.
	 */
	private Formula topFormula;
	/**
	 * Internal structure that allows to detect the formula sub-tree that contains a formula implementing
	 * {@link NonCacheableFormula} interface.
	 */
	private final Stack<AtomicBoolean> nonCacheableScope = new Stack<>();

	/**
	 * Returns all possible variants fo the original {@link #topFormula} tree where cacheable parts are exchanged to
	 * the {@link CachePayloadHeader} counterparts one by one.
	 */
	@Nonnull
	public List<Formula> getFormulaVariants() {
		final List<Formula> formulaVariants = new ArrayList<>(this.replacements.size());
		for (Entry<Formula, CachePayloadHeader> entry : this.replacements.entrySet()) {
			formulaVariants.add(
				FormulaCloner.clone(
					this.topFormula,
					formula -> entry.getKey() == formula ? (Formula) entry.getValue() : formula
				)
			);
		}
		return formulaVariants;
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		if (this.topFormula == null) {
			this.topFormula = formula;
		}
		this.nonCacheableScope.push(new AtomicBoolean());
		// when we detect non cacheable scope, we don't examine the child formulas
		if (!(formula instanceof NonCacheableFormulaScope)) {
			for (Formula innerFormula : formula.getInnerFormulas()) {
				innerFormula.accept(this);
			}
		}
		// if the formula itself is cacheable and doesn't contain non-cacheable formula
		if (formula instanceof CacheableFormula cacheableFormula && !this.nonCacheableScope.peek().get()) {
			// register it for replacement
			this.replacements.put(
				formula,
				// we don't care about hashes here - we just need some throw away cacheable form of the formula
				cacheableFormula.toSerializableFormula(this.replacements.size(), CacheSupervisor.createHashFunction())
			);
		}
		// if the formula itself is non-cacheable we need to mark the parent scope as non-cacheable
		final AtomicBoolean containsUserFilter = this.nonCacheableScope.pop();
		if (!this.nonCacheableScope.isEmpty() && (formula instanceof NonCacheableFormula || containsUserFilter.get())) {
			this.nonCacheableScope.peek().set(true);
		}
	}

}
