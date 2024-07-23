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
	// contains associated data `warrantySpecification` if fetched and not null
	@AssociatedData(name = "warrantySpecification", localized = true)
	@Nullable String warrantySpecification,

	// contains associated data `warrantySpecification` if fetched and not null
	@AssociatedDataRef("warrantySpecification")
	@Nullable String warrantySpecificationAgain,

	// contains associated data `parameters` or null if not fetched or not set
	@AssociatedDataRef("parameters")
	@Nullable String[] parameters,

	// contains associated data `parameters` or empty collection if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull Collection<String> parametersAsCollection,

	// contains associated data `parameters` or empty list if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull List<String> parametersAsList,

	// contains associated data `parameters` or empty set if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull Set<String> parametersAsSet,

	// contains associated data `localization` or null if not fetched or not set
	@AssociatedDataRef("localization")
	@Nullable Localization localization

) {

	public record Localization(
		@Nonnull Map<String, String> texts
	) { }

}
