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

package io.evitadb.core.query.algebra.utils.visitor;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * FormulaFinder allows finding out a formula that is instance of requested type in entire formula tree.
 * Returns {@link Set} of found {@link Formula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FormulaFinder<T> implements FormulaVisitor {
	private static final Predicate<Formula> NO_SKIP_PREDICATE = formula -> false;

	/**
	 * Predicate for testing the currently visited formula. Formulas that are truly tested by this predicate will
	 * be returned.
	 */
	private final Predicate<Formula> predicate;
	/**
	 * Predicate for testing the currently visited formula. Formulas that are truly tested by this predicate will be
	 * skipped from further investigation as well as their formula subtree.
	 */
	private final Predicate<Formula> skipPredicate;
	/**
	 * Should finder stop on the node that matches the requested type or should it continue searching its children
	 * deeply?
	 */
	private final LookUp lookUp;
	/**
	 * Result set of found formulas.
	 */
	@Getter private final Set<T> result = new LinkedHashSet<>();

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces collection of formulas that are of
	 * passed `lookedUpFormula` type.
	 */
	public static <T> Collection<T> find(@Nonnull Formula formulaToSearch, @Nonnull Class<T> lookedUpFormula, @Nonnull LookUp lookUp) {
		final FormulaFinder<T> visitor = new FormulaFinder<>(lookedUpFormula::isInstance, NO_SKIP_PREDICATE, lookUp);
		formulaToSearch.accept(visitor);
		return visitor.getResult();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces collection of formulas that are of
	 * passed `lookedUpFormula` type. This method excludes the `formulaToSearch` itself.
	 */
	public static <T> Collection<T> findAmongChildren(@Nonnull Formula formulaToSearch, @Nonnull Class<T> lookedUpFormula, @Nonnull LookUp lookUp) {
		final FormulaFinder<T> visitor = new FormulaFinder<>(lookedUpFormula::isInstance, NO_SKIP_PREDICATE, lookUp);
		for (Formula childFormula : formulaToSearch.getInnerFormulas()) {
			childFormula.accept(visitor);
		}
		return visitor.getResult();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces a collection of formulas that are of
	 * passed `lookedUpFormula` type.
	 */
	public static <T> Collection<T> find(@Nonnull Formula formulaToSearch, @Nonnull Predicate<Formula> predicate, @Nonnull LookUp lookUp) {
		final FormulaFinder<T> visitor = new FormulaFinder<>(predicate, NO_SKIP_PREDICATE, lookUp);
		formulaToSearch.accept(visitor);
		return visitor.getResult();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces a collection of formulas that are of
	 * passed `lookedUpFormula` type. This method excludes the `formulaToSearch` itself.
	 */
	public static <T> Collection<T> findAmongChildren(@Nonnull Formula formulaToSearch, @Nonnull Predicate<Formula> predicate, @Nonnull LookUp lookUp) {
		final FormulaFinder<T> visitor = new FormulaFinder<>(predicate, NO_SKIP_PREDICATE, lookUp);
		for (Formula childFormula : formulaToSearch.getInnerFormulas()) {
			childFormula.accept(visitor);
		}
		return visitor.getResult();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces a collection of formulas that are of
	 * passed `lookedUpFormula` type.
	 */
	public static <T> Collection<T> find(@Nonnull Formula formulaToSearch, @Nonnull Predicate<Formula> predicate, @Nonnull Predicate<Formula> skipPredicate, @Nonnull LookUp lookUp) {
		final FormulaFinder<T> visitor = new FormulaFinder<>(predicate, skipPredicate, lookUp);
		formulaToSearch.accept(visitor);
		return visitor.getResult();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces a collection of formulas that are of
	 * passed `lookedUpFormula` type. This method excludes the `formulaToSearch` itself.
	 */
	public static <T> Collection<T> findAmongChildren(@Nonnull Formula formulaToSearch, @Nonnull Predicate<Formula> predicate, @Nonnull Predicate<Formula> skipPredicate, @Nonnull LookUp lookUp) {
		final FormulaFinder<T> visitor = new FormulaFinder<>(predicate, skipPredicate, lookUp);
		for (Formula childFormula : formulaToSearch.getInnerFormulas()) {
			childFormula.accept(visitor);
		}
		return visitor.getResult();
	}

	private FormulaFinder(@Nonnull Predicate<Formula> predicate, @Nonnull Predicate<Formula> skipPredicate, @Nonnull LookUp lookUp) {
		this.predicate = predicate;
		this.skipPredicate = skipPredicate;
		this.lookUp = lookUp;
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		if (!this.skipPredicate.test(formula)) {
			if (this.predicate.test(formula)) {
				//noinspection unchecked
				this.result.add((T) formula);
				if (this.lookUp == LookUp.DEEP) {
					for (Formula innerFormula : formula.getInnerFormulas()) {
						innerFormula.accept(this);
					}
				}
			} else {
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
			}
		}
	}

	/**
	 * Should finder stop on the node that matches the requested type or should it continue searching its children
	 * deeply?
	 */
	public enum LookUp {
		SHALLOW, DEEP
	}
}
