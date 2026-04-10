/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.algebra.utils;

import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Static factory for creating boolean formula containers ({@link OrFormula}, {@link AndFormula}, {@link NotFormula})
 * that automatically simplify the result based on the number and type of inner formulas:
 *
 * - **zero** children → {@link EmptyFormula#INSTANCE}
 * - **one** child → the child itself (no wrapping container)
 * - **multiple** children → the appropriate boolean container with nested same-type containers flattened
 *
 * The {@link #or(Supplier, Formula...)} overload additionally handles {@link FutureNotFormula} post-processing
 * and eagerly merges all-{@link ConstantFormula} inputs into a single {@link ConstantFormula} via RoaringBitmap OR.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FormulaFactory {

	/**
	 * Creates a disjunction (OR) over `innerFormulas` with special handling for negation and constant folding.
	 *
	 * Processing order:
	 *
	 * 1. empty array → {@link EmptyFormula#INSTANCE}
	 * 2. single formula → returned as-is
	 * 3. first element is {@link FutureNotFormula} → delegates to
	 *    {@link FutureNotFormula#postProcess(Formula[], EnclosingContainerRelation, Supplier)} to compose
	 *    the final {@link NotFormula}
	 * 4. all elements are {@link ConstantFormula} or {@link EmptyFormula} → eagerly computes the bitmap union
	 *    via {@link RoaringBitmap#or(RoaringBitmap...)} and returns a single {@link ConstantFormula}
	 * 5. otherwise → wraps in {@link OrFormula}
	 *
	 * @param superSetFormulaSupplier supplies the superset formula used by {@link FutureNotFormula} post-processing
	 *                                when the negation has no positive sibling to serve as superset
	 * @param innerFormulas           formulas to combine with logical OR
	 * @return simplified formula representing the disjunction
	 */
	@Nonnull
	public static Formula or(@Nonnull Supplier<Formula> superSetFormulaSupplier, @Nonnull Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0];
		} else if (innerFormulas[0] instanceof FutureNotFormula) {
			return FutureNotFormula.postProcess(
				innerFormulas,
				EnclosingContainerRelation.DISJUNCTION,
				superSetFormulaSupplier
			);
		} else {
			/* this check and transformation enables prefetching for simple cases */
			boolean allConstantOrEmpty = true;
			for (final Formula value : innerFormulas) {
				if (!(value instanceof ConstantFormula || value instanceof EmptyFormula)) {
					allConstantOrEmpty = false;
					break;
				}
			}
			if (allConstantOrEmpty) {
				// count ConstantFormula instances first to pre-size the array
				int constantCount = 0;
				for (final Formula formula : innerFormulas) {
					if (formula instanceof ConstantFormula) {
						constantCount++;
					}
				}
				final RoaringBitmap[] bitmaps = new RoaringBitmap[constantCount];
				int idx = 0;
				for (final Formula innerFormula : innerFormulas) {
					if (innerFormula instanceof ConstantFormula constantFormula) {
						final Bitmap delegate = constantFormula.getDelegate();
						bitmaps[idx++] = delegate instanceof RoaringBitmapBackedBitmap rbbb ?
							rbbb.getRoaringBitmap() :
							RoaringBitmap.bitmapOf(delegate.getArray());
					}
				}
				return bitmaps.length == 0
					? EmptyFormula.INSTANCE
					: new ConstantFormula(new BaseBitmap(RoaringBitmap.or(bitmaps)));
			} else {
				return new OrFormula(innerFormulas);
			}
		}
	}

	/**
	 * Creates a disjunction (OR) over `innerFormulas` with automatic flattening of nested {@link OrFormula}
	 * containers via {@link #getMergedOrFormulas(Formula...)}. Returns {@link EmptyFormula#INSTANCE} for an empty
	 * array and the single element for a one-element array.
	 *
	 * @param innerFormulas formulas to combine with logical OR
	 * @return simplified formula representing the disjunction
	 */
	@Nonnull
	public static Formula or(@Nonnull Formula... innerFormulas) {
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
	 * Creates a conjunction (AND) over `innerFormulas` with automatic flattening of nested {@link AndFormula}
	 * containers via {@link #getMergedAndFormulas(Formula...)}. Returns {@link EmptyFormula#INSTANCE} for an empty
	 * array and the single element for a one-element array.
	 *
	 * @param innerFormulas formulas to combine with logical AND
	 * @return simplified formula representing the conjunction
	 */
	@Nonnull
	public static Formula and(@Nonnull Formula... innerFormulas) {
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
	 * Creates a {@link NotFormula} that subtracts the `subtracted` bitmap from the `superSet` bitmap.
	 *
	 * @param subtracted the formula whose results are removed from the superset
	 * @param superSet   the formula providing the base set to subtract from
	 * @return a {@link NotFormula} representing `superSet \ subtracted`
	 */
	@Nonnull
	public static Formula not(@Nonnull Formula subtracted, @Nonnull Formula superSet) {
		return new NotFormula(
			subtracted, superSet
		);
	}

	/**
	 * Flattens nested {@link OrFormula} containers so that a single OR product is computed instead of multiple
	 * nested ones. Each encountered {@link OrFormula} has its children and any additional {@link OrFormula#getBitmaps()
	 * bitmaps} (wrapped as {@link ConstantFormula}) promoted to the same level as sibling formulas.
	 *
	 * @param formulas the input formulas that may contain nested {@link OrFormula} instances
	 * @return a flat array with nested OR formulas unwrapped
	 */
	@Nonnull
	private static Formula[] getMergedOrFormulas(@Nonnull Formula... formulas) {
		final CompositeObjectArray<Formula> mergedFormulas = new CompositeObjectArray<>(Formula.class);
		for (Formula innerFormula : formulas) {
			if (innerFormula instanceof OrFormula orFormula) {
				mergedFormulas.addAll(innerFormula.getInnerFormulas(), 0, innerFormula.getInnerFormulas().length);
				final Bitmap[] bitmaps = orFormula.getBitmaps();
				for (final Bitmap bitmap : bitmaps) {
					mergedFormulas.add(new ConstantFormula(bitmap));
				}
			} else {
				mergedFormulas.add(innerFormula);
			}
		}
		return mergedFormulas.toArray();
	}

	/**
	 * Flattens nested {@link AndFormula} containers so that a single AND product is computed instead of multiple
	 * nested ones. Each encountered {@link AndFormula} has its children and any additional {@link AndFormula#getBitmaps()
	 * bitmaps} (wrapped as {@link ConstantFormula}) promoted to the same level as sibling formulas.
	 *
	 * @param formulas the input formulas that may contain nested {@link AndFormula} instances
	 * @return a flat array with nested AND formulas unwrapped
	 */
	@Nonnull
	private static Formula[] getMergedAndFormulas(@Nonnull Formula... formulas) {
		final CompositeObjectArray<Formula> mergedFormulas = new CompositeObjectArray<>(Formula.class);
		for (Formula innerFormula : formulas) {
			if (innerFormula instanceof AndFormula andFormula) {
				mergedFormulas.addAll(innerFormula.getInnerFormulas(), 0, innerFormula.getInnerFormulas().length);
				final Bitmap[] bitmaps = andFormula.getBitmaps();
				for (final Bitmap bitmap : bitmaps) {
					mergedFormulas.add(new ConstantFormula(bitmap));
				}
			} else {
				mergedFormulas.add(innerFormula);
			}
		}
		return mergedFormulas.toArray();
	}

	/**
	 * Utility class — not instantiable.
	 */
	private FormulaFactory() {
	}

}
