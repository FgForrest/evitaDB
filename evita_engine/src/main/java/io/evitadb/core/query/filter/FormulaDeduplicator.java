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
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Merges formulas that compute the same result over the same data into one instance, so a result memoized on that
 * instance is reused wherever the computation occurs. Formulas merge when their {@link FormulaKey keys} are equal; an
 * opaque key equals only its own holder, so a formula with one merges with no other formula.
 *
 * The first formula completed with a key becomes its canonical instance. A later formula whose key is interned already
 * is replaced by the canonical instance before its inner formulas are visited. Any other formula is interned after its
 * inner formulas, so it is first rebuilt over their canonical instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class FormulaDeduplicator extends FormulaCloner implements FormulaPostProcessor {
	/** Original tree, also returned when the cloner has not been visited yet. */
	private final Formula originalFormula;
	/** Canonical instances, indexed by their detached computation keys. */
	private final Map<FormulaKey, Formula> formulaCache = CollectionUtils.createHashMap(64);

	/**
	 * Creates a deduplicator for one tree, which it returns unchanged until it has visited the tree.
	 *
	 * @param originalFormula the tree to deduplicate
	 */
	public FormulaDeduplicator(@Nonnull Formula originalFormula) {
		super(formula -> formula);
		this.originalFormula = originalFormula;
	}

	@Nonnull
	@Override
	public Formula getPostProcessedFormula() {
		final Formula result = getResultClone();
		this.formulaCache.clear();
		return result == null ? this.originalFormula : result;
	}

	/**
	 * Replaces a formula whose key is interned already by the canonical instance, so the subtree of a duplicate is
	 * neither visited nor rebuilt. A visit would reach the same instance: a canonical instance has distinct canonical
	 * children, so a formula with its key keeps every child when the children are interned, and its rebuild carries
	 * the same key.
	 */
	@Nonnull
	@Override
	protected Formula preProcess(@Nonnull Formula formula) {
		final Formula canonicalFormula = this.formulaCache.get(formula.getKey());
		return canonicalFormula == null ? formula : canonicalFormula;
	}

	/**
	 * Interns the formula after the cloner has rebuilt it over canonical children. Key equality includes the
	 * computation and its data sources; opaque holders remain distinct through the key's instance-bound equality.
	 */
	@Nonnull
	@Override
	protected Formula postProcess(@Nonnull Formula formula) {
		final Formula existingFormula = this.formulaCache.putIfAbsent(formula.getKey(), formula);
		if (existingFormula != null) {
			return existingFormula;
		}
		return formula;
	}
}
