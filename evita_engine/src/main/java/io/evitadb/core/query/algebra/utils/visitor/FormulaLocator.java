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

/**
 * FormulaFinder allows finding out whether a formula tree contains formula of specified type.
 * Returns {@link Boolean}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FormulaLocator<T> implements FormulaVisitor {
	/**
	 * Requested {@link Formula} subtype to be looked up in the formula tree.
	 */
	private final Class<T> formulaType;
	/**
	 * Result or the locator.
	 */
	@Getter private boolean found;

	private FormulaLocator(@Nonnull Class<T> formulaType) {
		this.formulaType = formulaType;
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and returns true if tree contains at least single
	 * formula of passed `lookedUpFormula` type.
	 */
	public static <T> boolean contains(@Nonnull Formula formulaToSearch, @Nonnull Class<T> lookedUpFormula) {
		final FormulaLocator<T> visitor = new FormulaLocator<>(lookedUpFormula);
		formulaToSearch.accept(visitor);
		return visitor.isFound();
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		if (this.formulaType.isInstance(formula)) {
			this.found = true;
		} else {
			for (Formula innerFormula : formula.getInnerFormulas()) {
				innerFormula.accept(this);
				if (this.found) {
					break;
				}
			}
		}
	}

}
