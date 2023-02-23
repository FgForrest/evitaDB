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

package io.evitadb.core.query.algebra.utils;

import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.array.CompositeObjectArray;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Formula factory class contains static helper methods for creating basic boolean containers for set of formulas
 * that automatically adapt to the count and type of passed inner constraints (i.e. long vs. integer type).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FormulaFactory {

	/**
	 * Creates boolean OR query for passed `innerFormulas`. If the array is empty, it returns `emptyFormula`.
	 * If array contains exactly one inner formula, only this formula is returned, otherwise OR formula container
	 * either for integer or long type is created and returned.
	 */
	public static Formula or(Supplier<Formula> superSetFormulaSupplier, Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0];
		} else if (innerFormulas[0] instanceof FutureNotFormula) {
			return FutureNotFormula.postProcess(
				innerFormulas,
				OrFormula::new,
				superSetFormulaSupplier
			);
		} else {
			return new OrFormula(innerFormulas);
		}
	}

	/**
	 * Creates boolean OR query for passed `innerFormulas`. If the array is empty, it returns `emptyFormula`.
	 * If array contains exactly one inner formula, only this formula is returned, otherwise OR formula container
	 * either for integer or long type is created and returned.
	 */
	public static Formula or(Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0];
		} else {
			final Formula[] mergedFormulas = getMergedOrFormulas(innerFormulas);
			return new OrFormula(mergedFormulas);
		}
	}

	/**
	 * Creates boolean AND query for passed `innerFormulas`. If the array is empty, it returns `emptyFormula`.
	 * If array contains exactly one inner formula, only this formula is returned, otherwise AND formula container
	 * either for integer or long type is created and returned.
	 */
	public static Formula and(Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0];
		} else {
			final Formula[] mergedFormulas = getMergedAndFormulas(innerFormulas);
			return new AndFormula(mergedFormulas);
		}
	}

	/**
	 * Creates boolean NOT query for passed formulas, either for integer or long type.
	 */
	public static Formula not(Formula subtracted, Formula superSet) {
		return new NotFormula(
			subtracted, superSet
		);
	}

	/**
	 * Iterates over formulas and if OR formula is found it gets unwrapped and inner formulas are propagated to the same
	 * level as other formulas. We expect that the result would be wrapped into an OR container and by this unwrapping
	 * we would need to compute only one OR product and not two or more separate ones.
	 */
	@Nonnull
	private static Formula[] getMergedOrFormulas(Formula... formulas) {
		final CompositeObjectArray<Formula> mergedFormulas = new CompositeObjectArray<>(Formula.class);
		for (Formula innerFormula : formulas) {
			if (innerFormula instanceof OrFormula) {
				mergedFormulas.addAll(innerFormula.getInnerFormulas(), 0, innerFormula.getInnerFormulas().length);
				Arrays.stream(((OrFormula) innerFormula).getBitmaps()).map(ConstantFormula::new).forEach(mergedFormulas::add);
			} else {
				mergedFormulas.add(innerFormula);
			}
		}
		return mergedFormulas.toArray();
	}

	/**
	 * Iterates over formulas and if AND formula is found it gets unwrapped and inner formulas are propagated to the same
	 * level as other formulas. We expect that the result would be wrapped into an AND container and by this unwrapping
	 * we would need to compute only one AND product and not two and more separate ones.
	 */
	@Nonnull
	private static Formula[] getMergedAndFormulas(Formula... formulas) {
		final CompositeObjectArray<Formula> mergedFormulas = new CompositeObjectArray<>(Formula.class);
		for (Formula innerFormula : formulas) {
			if (innerFormula instanceof AndFormula) {
				mergedFormulas.addAll(innerFormula.getInnerFormulas(), 0, innerFormula.getInnerFormulas().length);
				Arrays.stream(((AndFormula) innerFormula).getBitmaps()).map(ConstantFormula::new).forEach(mergedFormulas::add);
			} else {
				mergedFormulas.add(innerFormula);
			}
		}
		return mergedFormulas.toArray();
	}

	private FormulaFactory() {
	}

}
