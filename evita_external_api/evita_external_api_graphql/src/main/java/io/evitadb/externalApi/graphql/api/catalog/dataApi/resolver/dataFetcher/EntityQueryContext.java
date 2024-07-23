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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;

/**
 * This is the main context object that should be used as {@link DataFetchingEnvironment#getLocalContext()} for passing
 * useful data down the data fetcher tree of entity fetching. Those data passed should be crucial for a decision-making
 * of child data of root entity (e.g. pricing, locale,...).
 * This class and its subclasses are the only classes that should be used as local context for entity fetching to not
 * introduce any unexpected runtime cast errors in data fetchers as the {@link DataFetchingEnvironment} does not provide
 * any tools for specifying allowed types of local context beforehand.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Builder(toBuilder = true)
@Data
@RequiredArgsConstructor
public final class EntityQueryContext {

	private static final EntityQueryContext EMPTY = new EntityQueryContext(null, null, null, null, false);

	@Nullable
	private final Locale desiredLocale;
	@Nullable
	private final Currency desiredPriceInCurrency;
	@Nullable
	private final String[] desiredPriceInPriceLists;
	@Nullable
	private final OffsetDateTime desiredPriceValidIn;
	private final boolean desiredPriceValidInNow;

	@Nonnull
	public static EntityQueryContext empty() {
		return EMPTY;
	}
}
