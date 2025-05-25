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
import lombok.Data;

import javax.annotation.Nonnull;
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
	private final StringBuilder result = new StringBuilder(1024);
	/**
	 * Indentation used for distinguishing inner formulas in the tree.
	 */
	private final int indent;
	/**
	 * Contains true if the calculated results should contain precise primary keys.
	 */
	private final PrettyPrintStyle style;
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
		this.style = PrettyPrintStyle.NORMAL;
	}

	public PrettyPrintingFormulaVisitor(int indent) {
		this.indent = indent;
		this.style = PrettyPrintStyle.NORMAL;
	}

	public PrettyPrintingFormulaVisitor(int indent, @Nonnull PrettyPrintStyle style) {
		this.indent = indent;
		this.style = style;
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces description string.
	 */
	public static String toString(@Nonnull Formula formula) {
		final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor(3);
		formula.accept(visitor);
		return visitor.getResult();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces description string.
	 */
	public static String toStringVerbose(@Nonnull Formula formula) {
		final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor(3, PrettyPrintStyle.VERBOSE);
		formula.accept(visitor);
		return visitor.getResult();
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		this.result.append(" ".repeat(Math.max(0, this.level * this.indent)));
		final FormulaInstance alreadySeenFormula = this.formulasSeen.get(formula);
		if (alreadySeenFormula != null) {
			this.result.append("[Ref to #").append(alreadySeenFormula.getId()).append("] ");
		} else {
			final int id = this.formulasSeen.size();
			this.formulasSeen.put(formula, new FormulaInstance(id, formula));
			this.result.append("[#").append(id).append("] ");
		}
		if (this.style == PrettyPrintStyle.VERBOSE) {
			this.result.append(formula.toStringVerbose());
		} else {
			this.result.append(formula);
		}
		if (formula.getInnerFormulas().length > 0) {
			try {
				this.result.append(" → ")
					.append(this.style == PrettyPrintStyle.VERBOSE ? formula.compute() : " result count " + formula.compute().size());
			} catch (Exception ex) {
				this.result.append(" → ?");
			}
		}
		this.result.append("\n");
		this.level++;
		for (Formula innerFormula : formula.getInnerFormulas()) {
			innerFormula.accept(this);
		}
		this.level--;
	}

	/**
	 * Returns string with formula description.
	 */
	public String getResult() {
		return this.result.toString();
	}

	@Data
	private static class FormulaInstance {
		private final int id;
		private final Formula formula;
	}

	/**
	 * Defines the style of pretty-printing.
	 */
	public enum PrettyPrintStyle {

		/**
		 * Output is concise and contains only necessary information.
		 */
		NORMAL,
		/**
		 * Output is verbose and contains all possible information.
		 */
		VERBOSE

	}

}
