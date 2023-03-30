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
import io.evitadb.api.query.descriptor.annotation.ConstraintChildrenParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `parentsOfReference` require query can be used only
 * for [hierarchical entities](../model/entity_model.md#hierarchical-placement) and can have zero, one or more
 * [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html) arguments that specifies type of
 * hierarchical entity that this entity relates to. If argument is omitted, entity type of queried entity is used.
 * Constraint may have also inner require constraints that define how rich returned information should be (by default only
 * primary keys are returned, but full entities might be returned as well).
 *
 * When this require query is used an additional object is stored to result index. This data structure contains
 * information about referenced entity paths for each entity in the response.
 *
 * Example for returning parents of the category when entity type `product` is queried:
 *
 * ```
 * parentsOfReference('category')
 * parentsOfReference('category','brand')
 * ```
 *
 * Additional data structure by default returns only primary keys of those entities, but it can also provide full parent
 * entities when this form of query is used:
 *
 * ```
 * parentsOfReference('category', entityBody())
 * parentsOfReference('category', 'brand', entityBody())
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "parentsOfReference",
	shortDescription = "The constraint triggers computation of parent entities of the referenced hierarchical entities into response."
)
public class HierarchyParentsOfReference extends AbstractRequireConstraintContainer implements HierarchyConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {
	@Serial private static final long serialVersionUID = -8462717866711769929L;

	private HierarchyParentsOfReference(@Nonnull String[] referenceName, @Nonnull RequireConstraint[] requirements) {
		super(referenceName, requirements);
	}

	public HierarchyParentsOfReference(@Nonnull String referenceName) {
		super(new String[] { referenceName });
	}

	public HierarchyParentsOfReference(@Nonnull String... referenceName) {
		super(referenceName);
	}

	@ConstraintCreatorDef
	public HierarchyParentsOfReference(@Nonnull @ConstraintClassifierParamDef String referenceName,
	                                   @Nullable @ConstraintChildrenParamDef EntityFetch entityRequirement) {
		super(new String[] { referenceName }, entityRequirement);
	}

	public HierarchyParentsOfReference(@Nonnull String[] referenceName,
	                                   @Nullable EntityFetch entityRequirement) {
		super(referenceName, entityRequirement);
	}

	/**
	 * Returns entity types which parent structure should be loaded for selected entity.
	 */
	@Nonnull
	public String[] getReferenceNames() {
		return Arrays.stream(getArguments())
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns requirement constraints for the loaded entitye.
	 */
	@Nullable
	public EntityFetch getEntityRequirement() {
		return getChildren().length == 0 ? null : (EntityFetch) getChildren()[0];
	}

	@Override
	public boolean isApplicable() {
		return getArguments().length > 0;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyParentsOfReference(getReferenceNames(), children);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyParentsOfReference(
			Arrays.stream(newArguments)
				.map(String.class::cast)
				.toArray(String[]::new),
			getChildren()
		);
	}
}
