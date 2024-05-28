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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator.entity;

import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link EntityPrimaryKeyInSet} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityPrimaryKeyInSetTranslator implements FilteringConstraintTranslator<EntityPrimaryKeyInSet> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull EntityPrimaryKeyInSet entityPrimaryKeyInSet, @Nonnull FilterByVisitor filterByVisitor) {
		Assert.notNull(filterByVisitor.getSchema(), "Schema must be known!");
		final SuperSetMatchingPostProcessor superSetMatchingPostProcessor = filterByVisitor.registerFormulaPostProcessorIfNotPresent(
			SuperSetMatchingPostProcessor.class,
			() -> new SuperSetMatchingPostProcessor(filterByVisitor)
		);
		final int[] primaryKeys = entityPrimaryKeyInSet.getPrimaryKeys();
		final Formula requiredBitmap = ArrayUtils.isEmpty(primaryKeys) ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(
				new BaseBitmap(primaryKeys)
			);
		return filterByVisitor.applyOnIndexes(
			entityIndex -> {
				superSetMatchingPostProcessor.addSuperSet(entityIndex.getAllPrimaryKeys());
				return requiredBitmap;
			}
		);
	}

	/**
	 * This post processor will merge the result formula with super set formulas if the index is not queried by other
	 * constraints. If we don't do this, the formula may return primary keys that are not part of the index, just
	 * because they were part of the input filtering constraint. Consider query:
	 *
	 * filterBy: entityPrimaryKeyInSet(1, 2, 3)
	 *
	 * applied on index containing primary keys:
	 *
	 * [100, 200, 300]
	 *
	 * In this situation the result formula should be empty and not [1, 2, 3] as it would be without this post processor.
	 */
	@RequiredArgsConstructor
	private static class SuperSetMatchingPostProcessor implements FormulaPostProcessor {
		/**
		 * A variable that holds an instance of the FilterByVisitor class.
		 */
		private final FilterByVisitor filterByVisitor;
		/**
		 * Set of formulas representing the super set.
		 */
		private final LinkedHashSet<Bitmap> superSetFormulas = new LinkedHashSet<>(16);
		/**
		 * Contains the root of the final formula.
		 */
		private Formula resultFormula;

		@Nonnull
		@Override
		public Formula getPostProcessedFormula() {
			// if the index is queried by other constraints, we don't need to merge the super set formulas
			// because it's already more constrained than the super set
			if (filterByVisitor.isTargetIndexQueriedByOtherConstraints()) {
				return resultFormula;
			} else {
				// if the index is not queried by other constraints, we need to merge the super set formulas
				return FormulaFactory.and(
					FormulaFactory.or(
						superSetFormulas.stream()
							.map(it -> it.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(it))
							.toArray(Formula[]::new)
					),
					resultFormula
				);
			}
		}

		@Override
		public void visit(@Nonnull Formula formula) {
			// if the result formula is not set yet, set it to the current formula and return
			resultFormula = formula;
		}

		/**
		 * Adds the given Bitmap as a superSet formula.
		 *
		 * @param primaryKeysFormula the Bitmap containing the primary keys formula to be added as a superSet formula
		 */
		public void addSuperSet(@Nonnull Bitmap primaryKeysFormula) {
			// because we're adding bitmap to a set, the duplicates will be removed
			this.superSetFormulas.add(primaryKeysFormula);
		}
	}
}
