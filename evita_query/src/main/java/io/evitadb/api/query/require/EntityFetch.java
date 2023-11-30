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
 * The `entityFetch` requirement is used to trigger loading one or more entity data containers from the disk by its
 * primary key. This operation requires a disk access unless the entity is already loaded in the database cache
 * (frequently fetched entities have higher chance to stay in the cache).
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Brand"),
 *     filterBy(
 *         entityPrimaryKeyInSet(64703),
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code", "name")
 *         )
 *     )
 * )
 * </pre>
 *
 * See internal contents available for fetching in {@link EntityContentRequire}:
 *
 * - {@link AttributeContent}
 * - {@link AssociatedDataContent}
 * - {@link PriceContent}
 * - {@link HierarchyContent}
 * - {@link ReferenceContent}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "fetch",
	shortDescription = "Returns richer entities instead of just entity references (empty container returns only entity body).",
	supportedIn = {ConstraintDomain.GENERIC, ConstraintDomain.REFERENCE, ConstraintDomain.HIERARCHY, ConstraintDomain.FACET}
)
public class EntityFetch extends AbstractRequireConstraintContainer implements EntityFetchRequire {

	@Serial private static final long serialVersionUID = -781235795350040285L;

	protected EntityFetch(RequireConstraint[] requireConstraints) {
		super(requireConstraints);
	}

	public EntityFetch() {
		super();
	}

	@Creator
	public EntityFetch(@Nonnull @Child(uniqueChildren = true) EntityContentRequire... requirements) {
		super(requirements);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

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
		Assert.isTrue(anotherRequirement instanceof EntityFetch, "Only EntityFetch requirement can be combined with this one!");

		final EntityContentRequire[] combinedContentRequirements = Stream.concat(
				Arrays.stream(getRequirements()),
				Arrays.stream(anotherRequirement.getRequirements())
			)
			.collect(new EntityContentRequireCombiningCollector());

		//noinspection unchecked
		return (T) new EntityFetch(combinedContentRequirements);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityFetch(getChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new EntityFetch(children);
	}

}
