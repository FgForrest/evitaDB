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

package io.evitadb.api.query.require;

import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This interface marks all requirements that can be used for loading additional data to existing entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityContentRequire extends RequireConstraint {

	/**
	 * Combines two EntityFetchRequire requirements into one combined requirement.
	 * If one of the requirements is null, the non-null requirement is returned.
	 * If both are null, null is returned. If both are non-null, they are combined.
	 *
	 * @param <T> the type of the requirement which extends EntityFetchRequire
	 * @param a the first EntityFetchRequire requirement, can be null
	 * @param b the second EntityFetchRequire requirement, can be null
	 * @return the combined EntityFetchRequire requirement, or null if both are null
	 */
	@Nullable
	static <T extends EntityContentRequire> T combineRequirements(@Nullable T a, @Nullable T b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	/**
	 * Determines whether this requirement can be combined with another requirement of the same type and logically
	 * combinable.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @return true if the requirements can be combined, false otherwise
	 * @param <T> type of the requirement to be combined with
	 */
	<T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement);

	/**
	 * Method allows to combine two requirements of same type (that needs to be compatible with "this" type) into one
	 * combining the arguments of both of them.
	 */
	@Nonnull
	<T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement);

	/**
	 * Determines if the current requirement is fully contained within the provided requirement. Contained means that
	 * this requirement is not necessary because it will be fully satisfied by the provided `anotherRequirement`.
	 *
	 * @param anotherRequirement another requirement to be checked for containment
	 * @param <T> the type of the requirement which extends EntityContentRequire
	 * @return true if the current requirement is fully contained within the provided requirement, false otherwise
	 */
	<T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement);

}
