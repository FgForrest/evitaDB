/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable descriptor of which storage parts need to be loaded from the entity storage for a given expression.
 * Built at schema load time by {@link PathToPartialMapper} from the static analysis of expression paths, and used at
 * trigger time by the instantiator to fetch only the required data.
 *
 * @param needsEntityBody             whether the entity body storage part is needed (primary key, version, scope,
 *                                    locales, parent)
 * @param needsGlobalAttributes       whether the global (non-localized) attributes storage part is needed
 * @param neededAttributeLocales      set of locales for which locale-specific attribute storage parts are needed
 * @param needsReferences             whether the references storage part is needed
 * @param neededAssociatedDataNames   set of associated data names (global) that need to be loaded
 * @param neededAssociatedDataLocales set of locales for which localized associated data storage parts are needed
 */
public record StoragePartRecipe(
	boolean needsEntityBody,
	boolean needsGlobalAttributes,
	@Nonnull Set<Locale> neededAttributeLocales,
	boolean needsReferences,
	@Nonnull Set<String> neededAssociatedDataNames,
	@Nonnull Set<Locale> neededAssociatedDataLocales
) implements Serializable {
	@Serial private static final long serialVersionUID = -4829175610937284512L;

	/**
	 * Empty recipe that requires no storage parts to be loaded.
	 */
	public static final StoragePartRecipe EMPTY = new StoragePartRecipe(
		false, false, Set.of(), false, Set.of(), Set.of()
	);
}
