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

package io.evitadb.core.query.sort.translator;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.Sorter;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link FilterBy} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class OrderByTranslator implements OrderingConstraintTranslator<OrderBy>, ReferenceOrderingConstraintTranslator<OrderBy> {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull OrderBy orderConstraints, @Nonnull OrderByVisitor orderByVisitor) {
		return Stream.empty();
	}

	@Override
	public void createComparator(@Nonnull OrderBy orderConstraints, @Nonnull ReferenceOrderByVisitor orderByVisitor) {
		// no implementation here - we just need to traverse the contents
	}
}
