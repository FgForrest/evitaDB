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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The `entityGroupFetch` requirement is the group-entity counterpart to {@link EntityFetch}. While `entityFetch`
 * retrieves the bodies of the *referenced* entities, `entityGroupFetch` retrieves the bodies of the *group* entities
 * that those references belong to. It is only meaningful inside a {@link ReferenceContent} constraint.
 *
 * In evitaDB, references can be classified into named groups. For example, product parameters belong to parameter
 * groups. When displaying a product detail page, you may need not only the parameter values (fetched via
 * `entityFetch`) but also the group entity bodies (fetched via `entityGroupFetch`) to obtain group names and
 * attributes for display purposes.
 *
 * Like {@link EntityFetch}, `entityGroupFetch` accepts any combination of {@link EntityContentRequire}
 * sub-requirements to specify which data to load for each group entity:
 *
 * - {@link AttributeContent} — group entity attributes
 * - {@link AssociatedDataContent} — group entity associated data
 * - {@link PriceContent} — group entity prices
 * - {@link HierarchyContent} — group entity parent chain
 * - {@link ReferenceContent} — group entity's own references
 *
 * An empty `entityGroupFetch()` loads only the group entity body without any additional data containers.
 *
 * Duplicate sub-requirements of one kind are folded into a single requirement by the very same rule `entityFetch`
 * uses — see its "Two content requirements of the same kind in one entityFetch" section for the key each kind folds
 * by and for the pairs that are refused with an {@link EvitaInvalidUsageException}.
 *
 * Example — fetching product parameters together with their group entities:
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
 *             referenceContent(
 *                 "parameterValues",
 *                 entityGroupFetch(
 *                     attributeContent("code", "name")
 *                 )
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "groupFetch",
	shortDescription = "The constraint triggers loading full group entity bodies instead of just primary key references inside reference content contexts.",
	userDocsLink = "/documentation/query/requirements/fetching#entity-group-fetch",
	supportedIn = {ConstraintDomain.FACET, ConstraintDomain.REFERENCE}
)
public class EntityGroupFetch extends AbstractRequireConstraintContainer implements EntityFetchRequire {

	@Serial private static final long serialVersionUID = -781235795350040285L;

	private EntityGroupFetch(@Nonnull RequireConstraint[] requireConstraints) {
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

	/**
	 * Merges this group fetch with another one into a single fetch requesting the union of both bodies. The merge is
	 * the very same keyed fold that reduces duplicate requirements written side by side
	 * ({@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])}), applied to the concatenation
	 * of both requirement lists - so a united body follows exactly the precedence a body written once would.
	 *
	 * Containment is deliberately **not** consulted here: a `referenceContent("brand")` is contained within a
	 * `referenceContentAllWithAttributes()`, yet the two are resolved through different lookups (the reference-name
	 * specific requirement wins over the default one), and dropping the specific one would silently widen the body
	 * fetched for `brand`.
	 *
	 * @param anotherRequirement another group fetch to be merged in, NULL yields this very instance
	 * @param <T> type of the requirement to be combined with
	 * @return a new group fetch covering both this one and `anotherRequirement`
	 * @throws EvitaInvalidUsageException when two requirements of one kind contradict each other
	 * @throws GenericEvitaInternalError when `anotherRequirement` is not an `entityGroupFetch`
	 */
	@Nonnull
	@Override
	public <T extends EntityFetchRequire> T combineWith(@Nullable T anotherRequirement) {
		if (anotherRequirement == null) {
			//noinspection unchecked
			return (T) this;
		}

		if (anotherRequirement instanceof EntityGroupFetch anotherEntityFetch) {
			final EntityContentRequire[] combinedContentRequirements =
				EntityFetchRequire.combineDuplicateRequirements(
					Stream.concat(
							Arrays.stream(getRequirements()),
							Arrays.stream(anotherEntityFetch.getRequirements())
						)
						.toArray(EntityContentRequire[]::new)
				);

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
	public <T extends EntityFetchRequire> T combineDuplicateRequirements() {
		final EntityContentRequire[] requirements = getRequirements();
		final EntityContentRequire[] reduced = EntityFetchRequire.combineDuplicateRequirements(requirements);
		//noinspection unchecked
		return reduced == requirements ?
			(T) this :
			(T) (EntityGroupFetch) getCopyWithNewChildren(reduced, getAdditionalChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityGroupFetch(getChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new EntityGroupFetch(children);
	}
}
