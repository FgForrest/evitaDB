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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `facet` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
 * entity type in first argument and one or more
 * additional [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
 * arguments that represents [facets](../model/entity_model.md#facets) that entity is required to have in order to match
 * this query.
 *
 * Function returns true if entity has a facet for specified entity type and matches passed primary keys in additional
 * arguments. By matching we mean, that entity has to have any of its facet (with particular type) primary keys equal to at
 * least one primary key specified in additional arguments.
 *
 * Example:
 *
 * ```
 * query(
 * entities('product'),
 * filterBy(
 * userFilter(
 * facet('category', 4, 5),
 * facet('group', 7, 13)
 * )
 * )
 * )
 * ```
 *
 * Constraint may be used only in [user filter](#user-filter) container. By default, facets of the same type within same
 * group are combined by conjunction (OR), facets of different types / groups are combined by disjunction (AND). This
 * default behaviour can be controlled exactly by using any of following require constraints:
 *
 * - [facet groups conjunction](#facet-groups-conjunction) - changes relationship between facets in the same group
 * - [facet groups disjunction](#facet-groups-disjunction) - changes relationship between facet groups
 *
 * ***Note:** you may ask why facet relation is specified by [require](#require) and not directly part of
 * the [filter](#filter)
 * body. The reason is simple - facet relation in certain group is usually specified system-wide and doesn't change in time
 * frequently. This means that it could be easily cached and passing this information in an extra require simplifies query
 * construction process.*
 *
 * *Another reason is that we need to know relationships among facet groups even for types/groups that hasn't yet been
 * selected by the user in order to be able to compute [facet summary](#facet-summary) output.*
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inSet",
	shortDescription = "The constraint if entity has at least one of the passed facet primary keys.",
	supportedIn = ConstraintDomain.ENTITY
)
public class FacetInSet extends AbstractFilterConstraintLeaf implements FacetConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -4135466525683422992L;

	private FacetInSet(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public FacetInSet(@Nonnull @Classifier String referenceName,
	                  @Nonnull @Value Integer... facetId) {
		super(concat(referenceName, facetId));
	}

	/**
	 * Returns name of the entity this hierarchy query relates to.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns primary keys of all facets that entity must have in order to match.
	 */
	@Nonnull
	public int[] getFacetIds() {
		return Arrays.stream(getArguments())
			.skip(1)
			.mapToInt(Integer.class::cast)
			.toArray();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 2;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetInSet(newArguments);
	}
}
