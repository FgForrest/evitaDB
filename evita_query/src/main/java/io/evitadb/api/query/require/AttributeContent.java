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
 * The `attributeContent` requirement fetches one or more named entity or reference attributes into the returned entity
 * body. It is valid inside {@link EntityFetch} (for entity-level attributes) and inside {@link ReferenceContent}
 * (for reference-level attributes that are stored on the reference itself, not on the referenced entity).
 *
 * ## Locale handling
 *
 * Localized attributes are returned only when a locale context is established in the query — either via
 * {@link EntityLocaleEquals} in the filter, or via the `dataInLocales` require constraint. Without a locale context,
 * only non-localized (global) attributes are returned even if localized variants exist in the database.
 *
 * ## Storage and network behavior
 *
 * All attributes of an entity are stored together in a single disk record and loaded atomically. Specifying only a
 * subset of attribute names therefore does **not** reduce I/O; it only reduces the amount of data transmitted over the
 * network to the client. It is therefore perfectly acceptable to use `attributeContentAll()` when most attributes are
 * needed.
 *
 * ## Wildcard form
 *
 * Calling `attributeContent()` with no arguments (or using the factory method `attributeContentAll()`) fetches all
 * attributes of the entity or reference. The static constant {@link #ALL_ATTRIBUTES} represents this wildcard form
 * and can be reused without allocation. This variant is also used internally as the implicit child of
 * `referenceContentAllWithAttributes()`.
 *
 * ## Combining
 *
 * Multiple `attributeContent` requirements are merged automatically when the same entity fetch is assembled from
 * separate query fragments. The merged result is the union of all explicitly named attributes; if any fragment
 * requests `attributeContentAll()`, the wildcard takes precedence over all named requests.
 *
 * Example — fetching specific named attributes:
 *
 * ```
 * entityFetch(
 *     attributeContent("code", "name")
 * )
 * ```
 *
 * Example — fetching all attributes:
 *
 * ```
 * entityFetch(
 *     attributeContentAll()
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#attribute-content)
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

	private AttributeContent(@Nonnull Serializable... arguments) {
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
