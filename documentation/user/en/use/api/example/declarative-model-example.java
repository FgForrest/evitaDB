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

@Entity(
	allowedEvolution = {
		EvolutionMode.ADDING_LOCALES,
		EvolutionMode.ADDING_CURRENCIES
	}
)
@SortableAttributeCompound(
	name = "nameCodeCompound",
	description = "Compound attribute for sorting by name and code",
	attributeElements = {
		@AttributeSource(attributeName = "code", orderDirection = OrderDirection.DESC, orderBehaviour = OrderBehaviour.NULLS_FIRST),
		@AttributeSource(attributeName = "name")
	},
	scope = { Scope.LIVE }
)
public interface Product extends Serializable {
	@PrimaryKey
	int getId();

	@Attribute
	@Nonnull
	String getCode();

	@Attribute(localized = true)
	@Nonnull
	String getName();

	@Attribute(
		name = "manufacturedBefore",
		description = "How many years ago the product was manufactured.",
		deprecated = "This attribute is obsolete.",
		filterable = true
	)
	@Nonnull
	default int[] getYears() {
		// the default implementation defines default value
		return new int[] {1978,2005,2020};
	}

	@AssociatedData
	@Nonnull
	ReferencedFiles getReferencedFiles();

	@ParentEntity
	int getParentEntity();

	@PriceForSale
	PriceContract getSellingPrice();

	@Reference
	Brand getMarketingBrand();

	@Reference
	Brand[] getLicensingBrands();

	record ReferencedFiles(@Nonnull int... fileId) implements Serializable {}

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

		@Attribute
		String getMarket();

	}

}
