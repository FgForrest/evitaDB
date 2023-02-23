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
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link FormulaCloner}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FormulaClonerTest {
	private Formula formula;

	@BeforeEach
	void setUp() {
		formula = new AndFormula(
			new OrFormula(
				EmptyFormula.INSTANCE,
				new ConstantFormula(new ArrayBitmap(3)),
				new NotFormula(
					new ConstantFormula(new ArrayBitmap(5)),
					new ConstantFormula(new ArrayBitmap(5, 8, 10))
				)
			),
			new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			)
		);
	}

	@Test
	void shouldLeaveFormulaUntouched() {
		final Formula cloneResult = FormulaCloner.clone(formula, UnaryOperator.identity());
		assertSame(formula, cloneResult);
	}

	@Test
	void shouldReplaceEntireFormula() {
		final ConstantFormula replacedFormula = new ConstantFormula(new ArrayBitmap(7));
		final Formula cloneResult = FormulaCloner.clone(formula, examinedFormula -> replacedFormula);
		assertSame(replacedFormula, cloneResult);
	}

	@Test
	void shouldGetRidOfEmptyFormulas() {
		final Formula cloneResult = FormulaCloner.clone(formula, examinedFormula -> examinedFormula instanceof EmptyFormula ? null : examinedFormula);
		assertNotSame(formula, cloneResult);
		assertTrue(FormulaLocator.contains(formula, EmptyFormula.class));
		assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));

		assertNotSame(formula.getInnerFormulas()[0], cloneResult.getInnerFormulas()[0]);
		assertSame(formula.getInnerFormulas()[0].getInnerFormulas()[1], cloneResult.getInnerFormulas()[0].getInnerFormulas()[0]);
		assertSame(formula.getInnerFormulas()[0].getInnerFormulas()[2], cloneResult.getInnerFormulas()[0].getInnerFormulas()[1]);
		assertSame(formula.getInnerFormulas()[1], cloneResult.getInnerFormulas()[1]);
	}

	@Test
	void shouldHandleUnnecessaryFormulas() {
		final Formula cloneResult = FormulaCloner.clone(formula, examinedFormula -> examinedFormula instanceof UserFilterFormula ? null : examinedFormula);
		assertNotSame(formula, cloneResult);
		assertSame(formula.getInnerFormulas()[0], cloneResult);
	}

	@Test
	void shouldResolveIsWithin() {
		final Formula cloneResult = FormulaCloner.clone(
			formula, (formulaCloner, currentFormula) -> {
				if (formulaCloner.isWithin(UserFilterFormula.class)) {
					return null;
				} else {
					return currentFormula;
				}
			}
		);

		assertNotSame(formula, cloneResult);
		assertTrue(FormulaLocator.contains(formula, UserFilterFormula.class));

		assertSame(formula.getInnerFormulas()[0], cloneResult.getInnerFormulas()[0]);
		assertNotSame(formula.getInnerFormulas()[1], cloneResult.getInnerFormulas()[1]);
		assertEquals(0, cloneResult.getInnerFormulas()[1].getInnerFormulas().length);
	}
}