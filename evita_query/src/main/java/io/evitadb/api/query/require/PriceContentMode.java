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

import io.evitadb.dataType.SupportedEnum;

/**
 * Controls which price records are loaded and returned along with a fetched entity, as specified by the
 * {@link PriceContent} require constraint placed inside `entityFetch`.
 *
 * evitaDB stores multiple prices per entity (one per price-list / currency / validity combination). Returning all
 * of them is rarely necessary and can add significant payload, so this enum allows callers to balance completeness
 * against response size:
 *
 * - `NONE` — no prices are returned; useful when price data is not needed by the caller.
 * - `RESPECTING_FILTER` — only prices that are visible given the active {@link io.evitadb.api.query.filter.PriceInPriceLists}
 *   and related filter constraints are returned. This is the recommended mode for most queries: the client receives
 *   exactly the prices it can potentially display, without unnecessary data.
 * - `ALL` — all prices of the entity are returned regardless of any filter constraints. This mode is useful for
 *   back-office UIs or administrative tools that need to inspect the complete price structure of an entity.
 */
@SupportedEnum
public enum PriceContentMode {

	/**
	 * No prices are returned with the entity.
	 */
	NONE,
	/**
	 * Only prices matching the {@link io.evitadb.api.query.filter.PriceInPriceLists} filter constraint are returned.
	 */
	RESPECTING_FILTER,
	/**
	 * All prices of the entity are returned regardless of the filter constraints.
	 */
	ALL

}
