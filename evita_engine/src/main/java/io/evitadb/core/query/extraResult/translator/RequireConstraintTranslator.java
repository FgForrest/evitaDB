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

package io.evitadb.core.query.extraResult.translator;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementations of this interface translate specific {@link RequireConstraint}s to a
 * {@link ExtraResultProducer} that can return computed result with record ids that fulfill the requirement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@FunctionalInterface
public interface RequireConstraintTranslator<T extends RequireConstraint> {

	/**
	 * Translates the given {@link RequireConstraint} into an {@link ExtraResultProducer} that can produce
	 * additional results based on the provided constraints and the context of the execution.
	 *
	 * @param constraint The {@link RequireConstraint} to be translated, ensuring the additional results are
	 * computed for records satisfying this constraint.
	 * @param extraResultPlanningVisitor An instance of {@link ExtraResultPlanningVisitor} that aids in the
	 * translation process by providing context or additional functionalities needed during the execution plan creation.
	 * @return An {@link ExtraResultProducer} capable of generating computed results based on the given
	 * constraints, or null if the constraint cannot be translated into a producer.
	 */
	@Nullable
	ExtraResultProducer createProducer(@Nonnull T constraint, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor);

}
