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
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
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

}
