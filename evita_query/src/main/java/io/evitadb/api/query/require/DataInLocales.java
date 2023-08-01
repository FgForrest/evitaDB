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

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * This `dataInLocales` query is require query that accepts zero or more {@link Locale} arguments. When this
 * require query is used, result contains [entity attributes and associated data](../model/entity_model.md)
 * localized in required languages as well as global ones. If query contains no argument, global data and data
 * localized to all languages are returned. If query is not present in the query, only global attributes and
 * associated data are returned.
 *
 * **Note:** if {@link EntityLocaleEquals}is used in the filter part of the query and `dataInLanguage`
 * require query is missing, the system implicitly uses `dataInLanguage` matching the language in filter query.
 *
 * Only single `dataInLanguage` query can be used in the query.
 *
 * Example that fetches only global and `en-US` localized attributes and associated data (considering there are multiple
 * language localizations):
 *
 * ```
 * dataInLocales('en-US')
 * ```
 *
 * Example that fetches all available global and localized data:
 *
 * ```
 * dataInLocalesAll()
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "dataInLocales",
	shortDescription = "The constraint triggers fetching of the localized attributes or associated data in different/additional locales than the locale specified in filtering constraints (if any at all).",
	supportedIn = ConstraintDomain.ENTITY
)
public class DataInLocales extends AbstractRequireConstraintLeaf
	implements GenericConstraint<RequireConstraint>, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 4716406488516855299L;

	private static final String SUFFIX_ALL = "all";

	private DataInLocales(Serializable... arguments) {
		super(arguments);
	}

	@Creator(suffix = SUFFIX_ALL)
	public DataInLocales() {
		super();
	}

	@Creator
	public DataInLocales(@Nonnull Locale... locale) {
		super(locale);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(anotherRequirement instanceof DataInLocales, "Only DataInLanguage requirement can be combined with this one!");
		if (isAllRequested()) {
			return (T) this;
		} else if (((DataInLocales) anotherRequirement).isAllRequested()) {
			return anotherRequirement;
		} else {
			return (T) new DataInLocales(
				Stream.concat(
						Arrays.stream(getArguments()).map(Locale.class::cast),
						Arrays.stream(anotherRequirement.getArguments()).map(Locale.class::cast)
					)
					.distinct()
					.toArray(Locale[]::new)
			);
		}
	}

	/**
	 * Returns zero or more locales that should be used for retrieving localized data. Is no locale is returned all
	 * available localized data are expected to be returned.
	 */
	public Locale[] getLocales() {
		return Arrays.stream(getArguments()).map(Locale.class::cast).toArray(Locale[]::new);
	}

	/**
	 * Returns TRUE if all available languages were requested to load.
	 */
	public boolean isAllRequested() {
		return ArrayUtils.isEmpty(getArguments());
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new DataInLocales(newArguments);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return isAllRequested() ? Optional.of(SUFFIX_ALL) : Optional.empty();
	}
}
