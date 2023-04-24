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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `parents` require query can be used only
 * for [hierarchical entities](../model/entity_model.md#hierarchical-placement) and target the entity type that is
 * requested in the query. Constraint may have also inner require constraints that define how rich returned information
 * should be (by default only primary keys are returned, but full entities might be returned as well).
 *
 * When this require query is used an additional object is stored to result index. This data structure contains
 * information about referenced entity paths for each entity in the response.
 *
 * Example for returning parents of the same type as was queried (e.g. parent categories of filtered category):
 *
 * ```
 * parents()
 * ```
 *
 * Additional data structure by default returns only primary keys of those entities, but it can also provide full parent
 * entities when this form of query is used:
 *
 * ```
 * parents()
 * parents(entityBody())
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "parentsOfSelf",
	shortDescription = "The constraint triggers computation of parent entities of the same type as returned entities into response."
)
public class HierarchyParentsOfSelf extends AbstractRequireConstraintContainer implements HierarchyConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = -4386649995372804764L;

	public HierarchyParentsOfSelf() {
		super();
	}

	@Creator(silentImplicitClassifier = true)
	public HierarchyParentsOfSelf(@Nullable @Child EntityFetch entityRequirement) {
		super(entityRequirement);
	}

	private HierarchyParentsOfSelf(RequireConstraint[] children) {
		super(children);
	}

	/**
	 * Returns requirement constraints for the loaded entity.
	 */
	@Nullable
	public EntityFetch getEntityRequirement() {
		return getChildren().length == 0 ? null : (EntityFetch) getChildren()[0];
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyParentsOfSelf(children);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("This type of constraint doesn't support arguments!");
	}
}
