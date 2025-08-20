/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index;

import io.evitadb.dataType.Scope;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Key representing {@link CatalogIndex}. Since the index is the only index in catalog data storage we can implement it
 * as a constant without any additional data.
 *
 * @param scope scope of the index (archive or living data set)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record CatalogIndexKey(
	@Nonnull Scope scope
) implements IndexKey {
	@Serial private static final long serialVersionUID = 5767229804074771988L;

	@Nonnull
	@Override
	public String toString() {
		return StringUtils.capitalize(this.scope.name().toLowerCase()) + " catalog index";
	}

}
