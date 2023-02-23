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

package io.evitadb.core.query.algebra.utils.visitor;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import lombok.Data;

import java.util.IdentityHashMap;

/**
 * PrettyPrintingFormulaVisitor can describe {@link Formula} tree in user-friendly fashion producing single string
 * representing the formula tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PrettyPrintingFormulaVisitor implements FormulaVisitor {
	/**
	 * Builder that produces the output string.
	 */
	private final StringBuilder result = new StringBuilder();
	/**
	 * Indentation used for distinguishing inner formulas in the tree.
	 */
	private final int indent;
	/**
	 * This map keeps track of already seen formulas so that we can mark duplicate instances in tree as references
	 * to them.
	 */
	private final IdentityHashMap<Formula, FormulaInstance> formulasSeen = new IdentityHashMap<>();
	/**
	 * Temporary variable representing level of currently processed formula.
	 */
	private int level;

	public PrettyPrintingFormulaVisitor() {
		this.indent = 3;
	}

	public PrettyPrintingFormulaVisitor(int indent) {
		this.indent = indent;
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces description string.
	 */
	public static String toString(Formula formula) {
		final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor();
		formula.accept(visitor);
		return visitor.getResult();
	}

	@Override
	public void visit(Formula formula) {
		result.append(" ".repeat(Math.max(0, level * indent)));
		final FormulaInstance alreadySeenFormula = formulasSeen.get(formula);
		if (alreadySeenFormula != null) {
			result.append("[Ref to #").append(alreadySeenFormula.getId()).append("] ");
		} else {
			final int id = formulasSeen.size();
			formulasSeen.put(formula, new FormulaInstance(id, formula));
			result.append("[#").append(id).append("] ");
		}
		result.append(formula.toString()).append("\n");
		level++;
		for (Formula innerFormula : formula.getInnerFormulas()) {
			innerFormula.accept(this);
		}
		level--;
	}

	/**
	 * Returns string with formula description.
	 */
	public String getResult() {
		return result.toString();
	}

	@Data
	private static class FormulaInstance {
		private final int id;
		private final Formula formula;
	}

}
