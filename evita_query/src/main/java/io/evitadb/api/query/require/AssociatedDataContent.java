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

import io.evitadb.api.query.AssociatedDataConstraint;
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * This `associatedData` requirement changes default behaviour of the query engine returning only entity primary keys in
 * the result. When this requirement is used result contains entity bodies along with associated data with names
 * specified in one or more arguments of this requirement.
 *
 * This requirement implicitly triggers {@link EntityFetch} requirement because attributes cannot be returned without entity.
 * Localized associated data is returned according to {@link EntityLocaleEquals} query. Requirement might be combined
 * with {@link AttributeContent} requirement.
 *
 * Example:
 *
 * <pre>
 * associatedData("description", "gallery-3d")
 * </pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching the entity associated data of specified names into the returned entities.",
	supportedIn = ConstraintDomain.ENTITY,
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AssociatedDataContent extends AbstractRequireConstraintLeaf
	implements AssociatedDataConstraint<RequireConstraint>, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 4863284278176575291L;
	private static final String SUFFIX = "all";

	private AssociatedDataContent(Serializable... arguments) {
		super(arguments);
	}

	@Creator(suffix = SUFFIX)
	public AssociatedDataContent() {
		super();
	}

	@Creator
	public AssociatedDataContent(@Nonnull String... associatedDataNames) {
		super(associatedDataNames);
	}

	/**
	 * Returns names of associated data that should be loaded along with entity.
	 */
	@Nonnull
	public String[] getAssociatedDataNames() {
		return Arrays.stream(getArguments())
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns TRUE if all available associated data were requested to load.
	 */
	public boolean isAllRequested() {
		return ArrayUtils.isEmpty(getArguments());
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return isAllRequested() ? of(SUFFIX) : empty();
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(anotherRequirement instanceof AssociatedDataContent, "Only AssociatedData requirement can be combined with this one!");
		if (isAllRequested()) {
			return (T) this;
		} else if (((AssociatedDataContent) anotherRequirement).isAllRequested()) {
			return anotherRequirement;
		} else {
			return (T) new AssociatedDataContent(
				Stream.concat(
						Arrays.stream(getArguments()).map(String.class::cast),
						Arrays.stream(anotherRequirement.getArguments()).map(String.class::cast)
					)
					.distinct()
					.toArray(String[]::new)
			);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AssociatedDataContent(newArguments);
	}
}
