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
import io.evitadb.api.query.descriptor.annotation.Value;
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
 * This `attributes` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
 * this requirement is used result contains [entity bodies](entity_model.md) except `associated data` that could
 * become big. These type of data can be fetched either lazily or by specifying additional requirements in the query.
 *
 * This requirement implicitly triggers {@link EntityBodyFetch} requirement because attributes cannot be returned without entity.
 * [Localized interface](classes/localized_interface.md) attributes are returned according to {@link EntityLocaleEquals}
 * query.
 *
 * Example:
 *
 * ```
 * attributes()
 * ```
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
	public AttributeContent(@Nonnull @Value String... attributeName) {
		super(attributeName);
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
