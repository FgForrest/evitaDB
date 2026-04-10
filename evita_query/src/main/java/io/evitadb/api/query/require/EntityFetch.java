/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
 * The `entityFetch` requirement triggers loading the full entity body from storage. Without this requirement, query
 * results contain only entity primary keys. This operation may require a disk access unless the entity is already
 * present in the database cache — frequently accessed entities have a higher probability of remaining cached.
 *
 * `entityFetch` acts as a container for one or more {@link EntityContentRequire} sub-requirements that specify
 * which data containers to load. An empty `entityFetch()` (no sub-requirements) loads only the entity body
 * (locale, scope, and schema reference), but no attributes, associated data, prices, or references.
 *
 * ## Performance consideration
 *
 * Only fetch data you actually need. Each sub-requirement causes additional data to be loaded from disk or cache.
 * Fetching unnecessary data increases both I/O cost and network transfer size.
 *
 * ## Supported content requirements
 *
 * - {@link AttributeContent} — entity or reference attributes
 * - {@link AssociatedDataContent} — unstructured associated data
 * - {@link PriceContent} — price information in various modes
 * - {@link AccompanyingPriceContent} — additional prices alongside the selling price
 * - {@link HierarchyContent} — parent hierarchy chain
 * - {@link ReferenceContent} — references to other entities (with optional nested entity/group fetching)
 * - {@link DataInLocales} — localized data in specific or all locales
 *
 * ## Usage context
 *
 * `entityFetch` is valid in the top-level `require` clause, inside {@link ReferenceContent} (to load referenced entity
 * bodies), and inside {@link HierarchyContent} (to load bodies of parent hierarchy nodes).
 *
 * When multiple `entityFetch` requirements are combined (e.g., from different API layers), their sub-requirements are
 * merged via {@link EntityContentRequireCombiningCollector}, so the result always fetches the union of requested data.
 *
 * Example — fetching selected attributes of a Brand entity:
 *
 * ```
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
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-fetch)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "fetch",
	shortDescription = "The constraint triggers loading full entity bodies instead of just primary key references; its children control which parts of the entity are fetched.",
	userDocsLink = "/documentation/query/requirements/fetching#entity-fetch",
	supportedIn = {ConstraintDomain.GENERIC, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.HIERARCHY, ConstraintDomain.FACET}
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

	@Override
	public <T extends EntityFetchRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof EntityFetch anotherEntityFetch) {
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

		if (anotherRequirement instanceof EntityFetch anotherEntityFetch) {
			final EntityContentRequire[] combinedContentRequirements = Stream.concat(
					Arrays.stream(getRequirements()),
					Arrays.stream(anotherEntityFetch.getRequirements())
				)
				.collect(new EntityContentRequireCombiningCollector());

			//noinspection unchecked
			return (T) new EntityFetch(combinedContentRequirements);
		} else {
			throw new GenericEvitaInternalError(
				"Only entity fetch requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only entity fetch requirement can be combined with this one!"
			);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityFetch(getChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new EntityFetch(children);
	}

}
