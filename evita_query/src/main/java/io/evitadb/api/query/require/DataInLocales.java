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

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <pre>
 * dataInLocales("en-US")
 * </pre>
 *
 * Example that fetches all available global and localized data:
 *
 * <pre>
 * dataInLocalesAll()
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#data-in-locales">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "dataInLocales",
	shortDescription = "The constraint triggers fetching of the localized attributes or associated data in different/additional locales than the locale specified in filtering constraints (if any at all).",
	userDocsLink = "/documentation/query/requirements/fetching#data-in-locales",
	supportedIn = ConstraintDomain.ENTITY
)
public class DataInLocales extends AbstractRequireConstraintLeaf
	implements GenericConstraint<RequireConstraint>, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 4716406488516855299L;
	private static final String SUFFIX_ALL = "all";
	private LinkedHashSet<Locale> localesAsSet;
	private Locale[] locales;

	private DataInLocales(Serializable... arguments) {
		super(arguments);
	}

	@Creator(suffix = SUFFIX_ALL)
	public DataInLocales() {
		super();
	}

	@Creator
	public DataInLocales(@Nonnull Locale... locales) {
		super(locales);
	}

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof DataInLocales;
	}

	/**
	 * Returns zero or more locales that should be used for retrieving localized data. Is no locale is returned all
	 * available localized data are expected to be returned.
	 */
	public Locale[] getLocales() {
		if (this.locales == null) {
			this.locales = getLocalesAsSet().toArray(Locale[]::new);
		}
		return this.locales;
	}

	/**
	 * Returns zero or more locales that should be used for retrieving localized data as set. Is empty set is returned
	 * all available localized data are expected to be returned.
	 */
	@Nonnull
	public Set<Locale> getLocalesAsSet() {
		if (this.localesAsSet == null) {
			this.localesAsSet = Arrays.stream(getArguments())
				.map(Locale.class::cast)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		}
		return this.localesAsSet;
	}

	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof DataInLocales anotherDataInLocales) {
			if (anotherDataInLocales.isAllRequested()) {
				return true;
			} else if (!isAllRequested()) {
				return anotherDataInLocales.getLocalesAsSet().containsAll(getLocalesAsSet());
			}
		}
		return false;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof DataInLocales anotherDataInLocales) {
			if (isAllRequested()) {
				return (T) this;
			} else if (anotherDataInLocales.isAllRequested()) {
				return anotherRequirement;
			} else {
				final Set<Locale> localesAsSet = new LinkedHashSet<>(getLocalesAsSet());
				localesAsSet.addAll(anotherDataInLocales.getLocalesAsSet());
				return (T) new DataInLocales(
					localesAsSet.toArray(Locale[]::new)
				);
			}
		} else {
			throw new GenericEvitaInternalError(
				"Only data in locales requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only data in locales requirement can be combined with this one!"
			);
		}
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
