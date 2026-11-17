/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.sort.fulltext.translator;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Refuses a place weight that ordering translation reaches on its own. Weights never order anything themselves: the
 * planner reads them out of `relevanceText` before index selection, and {@link RelevanceTextTranslator} consumes its
 * whole weight subtree without visiting it, so a weight reaching this translator stands outside any `relevanceText`.
 * Registering it for the four weight constraints turns such a weight into a usage error naming the constraint,
 * instead of the ordering visitors' internal error for a constraint without a translator.
 */
public class PlaceWeightTranslator implements OrderingConstraintTranslator<OrderConstraint>,
	ReferenceOrderingConstraintTranslator<OrderConstraint>, SelfTraversingTranslator {
	/**
	 * Refuses a weight reached outside the relevance policy container.
	 *
	 * @param weight the misplaced place weight
	 * @param visitor the ordering visitor, unused
	 * @return never returns normally
	 * @throws EvitaInvalidUsageException always, naming the weight
	 */
	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull OrderConstraint weight, @Nonnull OrderByVisitor visitor) {
		throw misplaced(weight);
	}

	/**
	 * Refuses weights in fetched-reference ordering with the same public diagnostic.
	 *
	 * @param weight the misplaced place weight
	 * @param visitor the reference ordering visitor, unused
	 * @throws EvitaInvalidUsageException always, naming the weight
	 */
	@Override
	public void createComparator(@Nonnull OrderConstraint weight, @Nonnull ReferenceOrderByVisitor visitor) {
		throw misplaced(weight);
	}

	/**
	 * Identifies the complete misplaced constraint.
	 *
	 * @param weight the constraint printed in the message
	 * @return the exception to throw, it is not thrown here
	 */
	@Nonnull
	private static EvitaInvalidUsageException misplaced(@Nonnull OrderConstraint weight) {
		return new EvitaInvalidUsageException("Constraint `" + weight +
			"` can only be used as a place weight inside relevanceText!");
	}
}
