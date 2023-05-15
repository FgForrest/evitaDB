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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/** *
 * TOBEDONE JNO: docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The constraint narrows hierarchy within parent constraint to include specified hierarchy subtrees from search.",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyHaving extends AbstractFilterConstraintContainer implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -6950287451642746676L;
	private static final String CONSTRAINT_NAME = "having";

	@Creator
	public HierarchyHaving(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filterConstraint) {
		super(CONSTRAINT_NAME, NO_ARGS, filterConstraint);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from {@link HierarchyWithin}
	 * query.
	 */
	@Nonnull
	public FilterConstraint[] getFiltering() {
		return getChildren();
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Override
	public boolean isApplicable() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return this;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyHaving doesn't accept other than filtering constraints!"
		);
		return new HierarchyHaving(children);
	}
}
