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

package io.evitadb.core.query.filter.translator.entity;

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.locale.LocaleFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.entity.alternative.LocaleEntityToBitmapFilter;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link EntityLocaleEquals} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityLocaleEqualsTranslator implements FilteringConstraintTranslator<EntityLocaleEquals> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull EntityLocaleEquals entityLocaleEquals, @Nonnull FilterByVisitor filterByVisitor) {
		final Locale locale = entityLocaleEquals.getLocale();

		if (filterByVisitor.isEntityTypeKnown()) {
			filterByVisitor.registerFormulaPostProcessorIfNotPresent(
				new LocaleOptimizingPostProcessor()
			);

			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filterByVisitor,
					filterByVisitor.applyOnIndexes(
						index -> index.getRecordsWithLanguageFormula(locale)
					),
					new LocaleEntityToBitmapFilter(locale)
				);
			} else {
				return filterByVisitor.applyOnIndexes(
					index -> index.getRecordsWithLanguageFormula(locale)
				);
			}
		} else {
			return new EntityFilteringFormula(
				"entity locale equals filter",
				filterByVisitor,
				new LocaleEntityToBitmapFilter(locale)
			);
		}
	}

	/**
	 * This class postprocess the created {@link Formula} filtering tree and removes the {@link LocaleFormula} in case
	 * an {@link AttributeFormula} that uses the localized attribute index is indexed in the filtering conjunction tree.
	 * This will remove necessity to process AND conjunction with rather large index with all localized entity primary
	 * keys.
	 */
	private static class LocaleOptimizingPostProcessor extends FormulaCloner implements FormulaPostProcessor {
		/**
		 * Reference to the original unchanged {@link Formula}.
		 */
		private Formula originalFormula;
		/**
		 * Flag that signalizes that localized {@link AttributeFormula} was found in conjunctive scope.
		 */
		private boolean localizedAttributeFormulaFound;
		/**
		 * Flag that signalizes {@link #visit(Formula)} happens in conjunctive scope.
		 */
		protected boolean conjunctiveScope = true;

		public LocaleOptimizingPostProcessor() {
			super(
				(formulaCloner, formula) -> {
					final LocaleOptimizingPostProcessor clonerInstance = (LocaleOptimizingPostProcessor) formulaCloner;
					if (clonerInstance.originalFormula == null) {
						clonerInstance.originalFormula = formula;
					}
					if (formula instanceof final AttributeFormula attributeFormula) {
						clonerInstance.localizedAttributeFormulaFound = clonerInstance.localizedAttributeFormulaFound ||
							(attributeFormula.isLocalized() && clonerInstance.conjunctiveScope);
					} else if (formula instanceof SelectionFormula selectionFormula && selectionFormula.getDelegate() instanceof LocaleFormula) {
						// skip this formula
						return null;
					} else if (formula instanceof LocaleFormula) {
						// skip this formula
						return null;
					}
					// include the formula
					return formula;
				}
			);
		}

		@Override
		public void visit(Formula formula) {
			final boolean formerConjunctiveScope = this.conjunctiveScope;
			try {
				if (!FilterByVisitor.isConjunctiveFormula(formula.getClass())) {
					this.conjunctiveScope = false;
				}
				super.visit(formula);
			} finally {
				this.conjunctiveScope = formerConjunctiveScope;
			}
		}

		@Nonnull
		@Override
		public Formula getPostProcessedFormula() {
			return localizedAttributeFormulaFound ?
				getResultClone() : originalFormula;
		}

	}
}
