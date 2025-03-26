/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.filter.FilterBy;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Shared interface for all require constraints that relate to facet groups.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface FacetGroupsConstraint extends Constraint<RequireConstraint>, RequireConstraint, FacetConstraint<RequireConstraint> {

	/**
	 * Returns name of the reference name this constraint relates to.
	 */
	@Nonnull
	String getReferenceName();

	/**
	 * Returns level on which this relation type is applied to.
	 */
	@AliasForParameter("relation")
	@Nonnull
	FacetGroupRelationLevel getFacetGroupRelationLevel();

	/**
	 * Returns filter constraint that can be resolved to array of facet groups primary keys.
	 */
	@AliasForParameter("filterBy")
	@Nonnull
	Optional<FilterBy> getFacetGroups();

}
