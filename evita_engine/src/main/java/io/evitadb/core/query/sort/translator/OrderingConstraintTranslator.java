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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Implementations of this interface translate specific {@link OrderConstraint}s to a {@link Sorter} that can return
 * computed result with record ids that fulfill the requested ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@FunctionalInterface
public interface OrderingConstraintTranslator<T extends OrderConstraint> {

	/**
	 * Method creates the appropriate {@link Sorter} implementation for passed constraint.
	 */
	@Nonnull
	Stream<Sorter> createSorter(@Nonnull T t, @Nonnull OrderByVisitor orderByVisitor);

}
