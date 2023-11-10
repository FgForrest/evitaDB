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

import io.evitadb.api.query.AttributeConstraint;
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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * The `attributeContent` requirement is used to retrieve one or more entity or reference attributes. Localized attributes
 * are only fetched if there is a locale context in the query, either by using the {@link EntityLocaleEquals} filter
 * constraint or the dataInLocales require constraint.
 *
 * All entity attributes are fetched from disk in bulk, so specifying only a few of them in the `attributeContent`
 * requirement only reduces the amount of data transferred over the network. It's not bad to fetch all the attributes
 * of an entity using `attributeContentAll`.
 *
 * Example:
 *
 * <pre>
 * entityFetch(
 *    attributeContent("code", "name")
 * )
 * </pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching the entity attributes into the returned entities.",
	supportedIn = ConstraintDomain.ENTITY,
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeContent extends AbstractRequireConstraintLeaf
	implements AttributeConstraint<RequireConstraint>, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 869775256765143926L;
	public static final AttributeContent ALL_ATTRIBUTES = new AttributeContent();
	private static final String SUFFIX = "all";

	private AttributeContent(Serializable... arguments) {
		super(arguments);
	}

	@Creator(suffix = SUFFIX)
	public AttributeContent() {
		super();
	}

	@Creator
	public AttributeContent(@Nonnull String... attributeNames) {
		super(attributeNames);
	}

	/**
	 * Returns names of attributes that should be loaded along with entity.
	 */
	@Nonnull
	public String[] getAttributeNames() {
		return Arrays.stream(getArguments())
				.map(String.class::cast)
				.toArray(String[]::new);
	}

	/**
	 * Returns names of attributes that should be loaded along with entity.
	 */
	@Nonnull
	public Set<String> getAttributeNamesAsSet() {
		return Arrays.stream(getArguments())
			.map(String.class::cast)
			.collect(Collectors.toSet());
	}

	/**
	 * Returns TRUE if all available attributes were requested to load.
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
		Assert.isTrue(anotherRequirement instanceof AttributeContent, "Only Attributes requirement can be combined with this one!");
		if (isAllRequested()) {
			return (T) this;
		} else if (((AttributeContent) anotherRequirement).isAllRequested()) {
			return anotherRequirement;
		} else {
			return (T) new AttributeContent(
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
		return new AttributeContent(newArguments);
	}
}
