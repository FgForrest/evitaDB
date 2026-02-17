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

import io.evitadb.api.query.AssociatedDataConstraint;
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.exception.GenericEvitaInternalError;

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
 * The `associatedDataContent` requirement fetches one or more named associated data items into the returned entity
 * body. Associated data are arbitrary unstructured data payloads (JSON objects, binary blobs, complex records)
 * stored alongside an entity that are not used for filtering or sorting — they exist solely to be displayed or
 * processed by the application.
 *
 * ## Storage model
 *
 * Unlike attributes (which are stored together in a single record), each associated data item is persisted as an
 * independent record. Fetching only the items you actually need is therefore an important optimization — loading
 * every associated data item wastes both I/O bandwidth and memory when most items are not displayed on a given page.
 *
 * ## Locale handling
 *
 * Localized associated data (those with a locale tag) are returned only when a locale context is active in the query,
 * established via {@link EntityLocaleEquals} in the filter or via the `dataInLocales` require constraint. Without a
 * locale context, only non-localized (global) associated data items are included in the result.
 *
 * ## Wildcard form
 *
 * Calling `associatedDataContent()` with no arguments (or using `associatedDataContentAll()`) fetches all associated
 * data items stored on the entity. Use this only when all items are genuinely needed, since each item triggers a
 * separate disk read.
 *
 * ## Combining
 *
 * When multiple `associatedDataContent` requirements are merged, the result is the union of all named items. If any
 * fragment uses the no-argument (wildcard) form, it supersedes all named requests.
 *
 * Example — fetching two named associated data items:
 *
 * ```
 * entityFetch(
 *     associatedDataContent("description", "gallery-3d")
 * )
 * ```
 *
 * Example — fetching all associated data:
 *
 * ```
 * entityFetch(
 *     associatedDataContentAll()
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#associated-data-content)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching the entity associated data of specified names into the returned entities.",
	userDocsLink = "/documentation/query/requirements/fetching#associated-data-content",
	supportedIn = ConstraintDomain.ENTITY,
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AssociatedDataContent extends AbstractRequireConstraintLeaf
	implements AssociatedDataConstraint<RequireConstraint>, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 4863284278176575291L;
	private static final String SUFFIX = "all";
	private LinkedHashSet<String> associatedDataNamesAsSet;
	private String[] associatedDataNames;

	private AssociatedDataContent(@Nonnull Serializable... arguments) {
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
		if (this.associatedDataNames == null) {
			this.associatedDataNames = getAssociatedDataNamesAsSet().toArray(String[]::new);
		}
		return this.associatedDataNames;
	}

	/**
	 * Returns names of associated data that should be loaded along with entity.
	 */
	@Nonnull
	public Set<String> getAssociatedDataNamesAsSet() {
		if (this.associatedDataNamesAsSet == null) {
			this.associatedDataNamesAsSet = Arrays.stream(getArguments())
				.map(String.class::cast)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		}
		return this.associatedDataNamesAsSet;
	}

	/**
	 * Returns TRUE if all available associated data were requested to load.
	 */
	public boolean isAllRequested() {
		return getAssociatedDataNamesAsSet().isEmpty();
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return isAllRequested() ? of(SUFFIX) : empty();
	}

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof AssociatedDataContent;
	}

	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof AssociatedDataContent anotherAssociatedDataContent) {
			if (anotherAssociatedDataContent.isAllRequested()) {
				return true;
			} else if (!isAllRequested()) {
				return anotherAssociatedDataContent.getAssociatedDataNamesAsSet().containsAll(getAssociatedDataNamesAsSet());
			}
		}
		return false;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof AssociatedDataContent anotherAssociatedDataContent) {
			if (isAllRequested()) {
				return (T) this;
			} else if (anotherAssociatedDataContent.isAllRequested()) {
				return anotherRequirement;
			} else {
				final Set<String> associatedDataNamesAsSet = new LinkedHashSet<>(getAssociatedDataNamesAsSet());
				associatedDataNamesAsSet.addAll(anotherAssociatedDataContent.getAssociatedDataNamesAsSet());
				return (T) new AssociatedDataContent(
					associatedDataNamesAsSet.toArray(String[]::new)
				);
			}
		} else {
			throw new GenericEvitaInternalError(
				"Only associated data requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only associated data requirement can be combined with this one!"
			);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AssociatedDataContent(newArguments);
	}
}
