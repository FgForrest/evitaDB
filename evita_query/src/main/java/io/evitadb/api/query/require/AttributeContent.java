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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#attribute-content">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching the entity attributes into the returned entities.",
	userDocsLink = "/documentation/query/requirements/fetching#attribute-content",
	supportedIn = ConstraintDomain.ENTITY,
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeContent extends AbstractRequireConstraintLeaf
	implements AttributeConstraint<RequireConstraint>, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 869775256765143926L;
	public static final AttributeContent ALL_ATTRIBUTES = new AttributeContent();
	private static final String SUFFIX = "all";
	private LinkedHashSet<String> attributeNamesAsSet;
	private String[] attributeNames;

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
		if (this.attributeNames == null) {
			this.attributeNames = getAttributeNamesAsSet().toArray(String[]::new);
		}
		return this.attributeNames;
	}

	/**
	 * Returns names of attributes that should be loaded along with entity.
	 */
	@Nonnull
	public Set<String> getAttributeNamesAsSet() {
		if (this.attributeNamesAsSet == null) {
			this.attributeNamesAsSet = Arrays.stream(getArguments())
				.map(String.class::cast)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		}
		return this.attributeNamesAsSet;
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

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof AttributeContent;
	}

	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof AttributeContent anotherAttributeContent) {
			if (anotherAttributeContent.isAllRequested()) {
				return true;
			} else if (!isAllRequested()) {
				return anotherAttributeContent.getAttributeNamesAsSet().containsAll(getAttributeNamesAsSet());
			}
		}
		return false;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof AttributeContent anotherAttributeContent) {
			if (isAllRequested()) {
				return (T) this;
			} else if (anotherAttributeContent.isAllRequested()) {
				return anotherRequirement;
			} else {
				final Set<String> attributeNamesAsSet = new LinkedHashSet<>(getAttributeNamesAsSet());
				attributeNamesAsSet.addAll(anotherAttributeContent.getAttributeNamesAsSet());
				return (T) new AttributeContent(
					attributeNamesAsSet.toArray(String[]::new)
				);
			}
		} else {
			throw new GenericEvitaInternalError(
				"Only attributes requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only attributes requirement can be combined with this one!"
			);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeContent(newArguments);
	}
}
