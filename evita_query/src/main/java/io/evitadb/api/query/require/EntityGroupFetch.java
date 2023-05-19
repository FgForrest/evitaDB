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
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * TOBEDONE JNO docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "groupFetch",
	shortDescription = "Returns richer group entities instead of just entity references (empty container returns only entity body).",
	supportedIn = {ConstraintDomain.FACET}
)
public class EntityGroupFetch extends AbstractRequireConstraintContainer implements EntityFetchRequire {

	@Serial private static final long serialVersionUID = -781235795350040285L;

	private EntityGroupFetch(RequireConstraint[] requireConstraints) {
		super(requireConstraints);
	}

	public EntityGroupFetch() {
		super();
	}

	@Creator
	public EntityGroupFetch(@Nonnull @Child(uniqueChildren = true) EntityContentRequire... requirements) {
		super(requirements);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	/**
	 * Returns requirement constraints for the loaded entities.
	 */
	@Nonnull
	@Override
	public EntityContentRequire[] getRequirements() {
		return Arrays.stream(getChildren())
			.map(EntityContentRequire.class::cast)
			.toArray(EntityContentRequire[]::new);
	}

	@Nonnull
	@Override
	public <T extends EntityRequire> T combineWith(@Nullable T anotherRequirement) {
		if (anotherRequirement == null) {
			//noinspection unchecked
			return (T) this;
		}
		Assert.isTrue(anotherRequirement instanceof EntityGroupFetch, "Only EntityGroupFetch requirement can be combined with this one!");

		final EntityContentRequire[] combinedContentRequirements = Stream.concat(
				Arrays.stream(getRequirements()),
				Arrays.stream(anotherRequirement.getRequirements())
			)
			.collect(new EntityContentRequireCombiningCollector());

		//noinspection unchecked
		return (T) new EntityGroupFetch(combinedContentRequirements);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityGroupFetch(getChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new EntityGroupFetch(children);
	}
}
