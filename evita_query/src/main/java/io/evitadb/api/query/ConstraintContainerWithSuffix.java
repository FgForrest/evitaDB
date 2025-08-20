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

package io.evitadb.api.query;

import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;

/**
 * Interface must be implemented by all {@link ConstraintContainer} implementations that use {@link Creator} annotation with
 * specification of `suffix` attribute. Such constraints require initialization not by {@link Constraint#getName()}
 * but combination of {@link Constraint#getName()} and {@link #getSuffixIfApplied()} in order to be properly parsed.
 *
 * The {@link QueryConstraints} method that creates instance of this constraint should respect the suffix as well, so
 * that the EvitaQL and the Java notation stays aligned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ConstraintContainerWithSuffix extends ConstraintWithSuffix {

	/**
	 * This method is used in {@link ConstraintContainer#toString()} implementation to exclude the child that is used
	 * implicitly when this suffix is applied to this constraint.
	 *
	 * @param child to check
	 * @return true, if this child should be omitted in {@link ConstraintContainer#toString()}
	 */
	default boolean isChildImplicitForSuffix(@Nonnull Constraint<?> child) {
		return false;
	}

	/**
	 * This method is used in {@link ConstraintContainer#toString()} implementation to exclude the additional child that is used
	 * implicitly when this suffix is applied to this constraint.
	 *
	 * @param child to check
	 * @return true, if this child should be omitted in {@link ConstraintContainer#toString()}
	 */
	default boolean isAdditionalChildImplicitForSuffix(@Nonnull Constraint<?> child) {
		return false;
	}

}
