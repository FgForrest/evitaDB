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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The `entityGroupFetch` requirement is similar to {@link EntityFetch} but is used to trigger loading one or more
 * referenced group entities in the {@link ReferenceContent} parent.
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
 *             referenceContent(
 *                "parameterValues",
 *                entityGroupFetch(
 *                   attributeContent("code", "name")
 *                )
*              )
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
 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch">Visit detailed user documentation</a></p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "groupFetch",
	shortDescription = "Returns richer group entities instead of just entity references (empty container returns only entity body).",
	userDocsLink = "/documentation/query/requirements/fetching#entity-group-fetch",
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

	@Override
	public <T extends EntityFetchRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof EntityGroupFetch anotherEntityFetch) {
			return Arrays.stream(getRequirements())
				.allMatch(requirement -> Arrays.stream(anotherEntityFetch.getRequirements()).anyMatch(requirement::isFullyContainedWithin));
		}
		return false;
	}

	@Nonnull
	@Override
	public <T extends EntityFetchRequire> T combineWith(@Nullable T anotherRequirement) {
		if (anotherRequirement == null) {
			//noinspection unchecked
			return (T) this;
		}

		if (anotherRequirement instanceof EntityGroupFetch anotherEntityFetch) {
			final EntityContentRequire[] combinedContentRequirements = Stream.concat(
					Arrays.stream(getRequirements()),
					Arrays.stream(anotherEntityFetch.getRequirements())
				)
				.collect(new EntityContentRequireCombiningCollector());

			//noinspection unchecked
			return (T) new EntityGroupFetch(combinedContentRequirements);
		} else {
			throw new GenericEvitaInternalError(
				"Only entity group fetch requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only entity group fetch requirement can be combined with this one!"
			);
		}
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
