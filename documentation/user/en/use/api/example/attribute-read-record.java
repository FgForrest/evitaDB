/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

@EntityRef("Product")
public record MyEntity(
	// contains attribute `name` if fetched and not null
	@Attribute(name = "name", localized = true)
	@Nullable String name,

	// contains attribute `name` if fetched and not null
	@AttributeRef("name")
	@Nullable String nameAgain,

	// contains attribute `markets` or null if not fetched or not set
	@AttributeRef("markets")
	@Nullable String[] markets,

	// contains attribute `markets` or empty collection if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull Collection<String> marketsAsCollection,

	// contains attribute `markets` or empty list if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull List<String> marketsAsList,

	// contains attribute `markets` or empty set if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull Set<String> marketsAsSet

) {

}
