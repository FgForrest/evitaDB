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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HierarchyConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * This interface marks all filtering constraints that represent hierarchy filtering constraing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyFilterConstraint extends FilterConstraint, HierarchyConstraint<FilterConstraint> {

	/**
	 * Returns name of the reference this hierarchy query relates to.
	 * Returns null if reference name is not specified and thus the same entity type as "queried" should be used.
	 */
	@Nullable
	Optional<String> getReferenceName();

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the root entity.
	 */
	boolean isDirectRelation();

	/**
	 * Returns filtering constraints that return entities whose trees should be included from hierarchy query.
	 */
	@Nonnull
	FilterConstraint[] getHavingChildrenFilter();

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from hierarchy query.
	 */
	@Nonnull
	FilterConstraint[] getExcludedChildrenFilter();

}
