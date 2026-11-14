/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.http.routing;

import io.netty.util.AttributeKey;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;

/**
 * Named path captures selected by an endpoint router, in template order. Query data stays in the context query.
 * Arrays are privately owned by routing: names are shared with immutable route metadata and values belong to this
 * request. No array is exposed. The only production endpoint router is the leaf (outer API and catalog routers
 * are prefix routers), so captures from two endpoint routers cannot accumulate on a production request.
 * Wildcard tails have no production parameter reader and are deliberately absent here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
public final class RouteParameters {
	/** Request-local carrier; absent when the selected endpoint has no named captures. */
	public static final AttributeKey<RouteParameters> KEY = AttributeKey.valueOf(RouteParameters.class, "parameters");
	/** Declared name of each capture, in template order; shared with the immutable route metadata. */
	private final String[] names;
	/** Decoded value of each capture, parallel to `names`; owned by this request. */
	private final String[] values;

	/**
	 * Takes ownership of captured values and shares immutable route names. Callers must never modify either array
	 * after construction; this permits reuse of route metadata without copying it for each request.
	 *
	 * @param names  declared capture names in template order
	 * @param values decoded capture values, of the same length and order as `names`
	 */
	public RouteParameters(@Nonnull String[] names, @Nonnull String[] values) {
		this.names = names;
		this.values = values;
	}

	/** Returns the number of named path captures. */
	public int size() {
		return this.names.length;
	}

	/** Returns the original declared name at a template position. */
	@Nonnull
	public String nameAt(int index) {
		return this.names[index];
	}

	/** Returns the once-decoded value at that same template position. */
	@Nonnull
	public String valueAt(int index) {
		return this.values[index];
	}
}
