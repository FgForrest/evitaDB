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

package io.evitadb.core.query.filter.translator;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link FilterBy} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FilterByTranslator implements FilteringConstraintTranslator<FilterBy> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull FilterBy filterConstraints, @Nonnull FilterByVisitor filterByVisitor) {
		final Formula[] collectedFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
		if (ArrayUtils.isEmpty(collectedFormulas)) {
			return filterByVisitor.getSuperSetFormula();
		} else {
			return FutureNotFormula.postProcess(
				collectedFormulas,
				FormulaFactory::and,
				filterByVisitor::getSuperSetFormula
			);
		}
	}

}
