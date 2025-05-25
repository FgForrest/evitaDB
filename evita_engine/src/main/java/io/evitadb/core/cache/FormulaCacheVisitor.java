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

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FormulaCacheVisitor extends {@link FormulaCloner} and creates new clone of original "analysed" formula replacing all
 * costly formulas either with cached results, or their copies that communicate with {@link CacheAnteroom} so their
 * usage is being tracked and evaluated.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see CacheAnteroom#register(EvitaSessionContract, String, Formula, FormulaCacheVisitor)  for more details
 */
public class FormulaCacheVisitor extends FormulaCloner {

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces clone that may contain already
	 * cached results.
	 *
	 * @see CacheAnteroom#register(EvitaSessionContract, String, Formula, FormulaCacheVisitor)  for more details
	 */
	@Nonnull
	public static Formula analyse(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull Formula formulaToAnalyse,
		@Nonnull CacheAnteroom cacheAnteroom
	) {
		final FormulaCacheVisitor visitor = new FormulaCacheVisitor(
			evitaSession,
			entityType,
			cacheAnteroom
		);
		formulaToAnalyse.accept(visitor);
		return visitor.getResultClone();
	}

	private FormulaCacheVisitor(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull CacheAnteroom cacheAnteroom
	) {
		super((self, formula) -> cacheAnteroom.register(session, entityType, formula, (FormulaCacheVisitor) self));
	}

	/**
	 * Method is called from {@link CacheAnteroom} to traverse children of the passed formula using this visitor.
	 */
	@Nullable
	public Formula[] analyseChildren(@Nonnull Formula formula) {
		this.treeStack.push(new CacheSubTree());
		this.parents.push(formula);
		for (Formula innerFormula : formula.getInnerFormulas()) {
			innerFormula.accept(this);
		}
		this.parents.pop();
		final CacheSubTree theTree = (CacheSubTree) this.treeStack.pop();
		final Set<Formula> updatedChildren = theTree.getChildren();
		return theTree.containsNonCacheableFormula() ? null : updatedChildren.toArray(Formula[]::new);
	}

	/**
	 * We need to provide our extended implementation of {@link SubTree} for this implementation.
	 */
	@Override
	protected void pushContext(@Nonnull Deque<SubTree> stack, @Nonnull Formula formula) {
		stack.push(new CacheSubTree());
	}

	/**
	 * On pop context we also propagate information that the stack contains non-cacheable formula.
	 */
	@Override
	protected SubTree popContext(@Nonnull Deque<SubTree> stack) {
		final CacheSubTree subTree = (CacheSubTree) super.popContext(stack);
		// propagate information about user filter container
		if (subTree.containsNonCacheableFormula() && !stack.isEmpty()) {
			final CacheSubTree peek = (CacheSubTree) stack.peek();
			peek.setContainsNonCacheableFormula();
		}
		return subTree;
	}

	/**
	 * Extended implementation of the {@link FormulaCloner.SubTree} contract that is capable to store flag signalling
	 * that the subtree contains {@link NonCacheableFormula}.
	 */
	private static class CacheSubTree implements SubTree {
		@Getter private final Set<Formula> children = new LinkedHashSet<>();
		private final AtomicBoolean containsNonCacheableFormula = new AtomicBoolean();

		/**
		 * Returns TRUE if the subtree contains {@link NonCacheableFormula}.
		 */
		public boolean containsNonCacheableFormula() {
			return this.containsNonCacheableFormula.get();
		}

		public void setContainsNonCacheableFormula() {
			this.containsNonCacheableFormula.set(true);
		}

		@Override
		public void add(@Nonnull Formula formula) {
			this.children.add(formula);
			if (formula instanceof NonCacheableFormula) {
				this.containsNonCacheableFormula.set(true);
			}
		}
	}

}
