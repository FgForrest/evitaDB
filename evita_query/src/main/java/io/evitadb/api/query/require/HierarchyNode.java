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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * TOBEDONE JNO: docs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "node",
	shortDescription = "The constraint allows to locate the pivot hierarchy node.",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyNode extends AbstractRequireConstraintContainer implements HierarchyStopAtRequireConstraint {
	@Serial private static final long serialVersionUID = -7033476265993356981L;
	private static final String CONSTRAINT_NAME = "node";

	@Creator(silentImplicitClassifier = true)
	public HierarchyNode(@Nonnull @AdditionalChild FilterBy filterBy) {
		super(CONSTRAINT_NAME, new Serializable[0], new RequireConstraint[0], filterBy);
	}

	/**
	 * Contains filtering condition that identifies the pivot node. The filtering constraint must match exactly one
	 * pivot node.
	 */
	@Nonnull
	public FilterBy getFilterBy() {
		return Objects.requireNonNull(Optional.ofNullable(getAdditionalChild(FilterBy.class))
			.orElseThrow(() -> new IllegalStateException("Hierarchy node expects FilterBy as its single inner constraint!")));
	}

	@Override
	public boolean isApplicable() {
		return getAdditionalChildrenCount() == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(children), "Inner constraints of different type than FilterBy are not expected.");
		Assert.isTrue(additionalChildren.length == 1, "HierarchyNode expect FilterBy inner constraint!");
		for (Constraint<?> constraint : additionalChildren) {
			Assert.isTrue(
				constraint instanceof FilterBy,
				"Constraint HierarchyNode accepts only FilterBy as inner constraint!"
			);
		}

		return new HierarchyNode((FilterBy) additionalChildren[0]);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "HierarchyNode container accepts no arguments!");
		return this;
	}

}
