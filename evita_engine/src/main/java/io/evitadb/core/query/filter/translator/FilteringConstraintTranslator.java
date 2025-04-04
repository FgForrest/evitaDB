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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.FilterByVisitor;

import javax.annotation.Nonnull;

/**
 * Implementations of this interface translate specific {@link FilterConstraint}s to a {@link AbstractFormula} that can return
 * computed result with record ids that fulfill the query condition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@FunctionalInterface
public interface FilteringConstraintTranslator<T extends FilterConstraint> {

	/**
	 * Translates the query to the {@link AbstractFormula} that returns primary keys that match the query.
	 */
	@Nonnull
	Formula translate(@Nonnull T t, @Nonnull FilterByVisitor filterByVisitor);

	/**
	 * Provides a no-operation translator for filter constraints. This translator returns a SkipFormula instance
	 * which acts as a placeholder formula, indicating that the constraint should be skipped and excluded from
	 * the computational tree.
	 *
	 * @param <T> the type of filter constraint
	 * @return a no-operation translator for the specified filter constraint type
	 */
	@Nonnull
	static <T extends FilterConstraint> FilteringConstraintTranslator<T> noOpTranslator() {
		return (constraint, filterByVisitor) -> SkipFormula.INSTANCE;
	}

}
