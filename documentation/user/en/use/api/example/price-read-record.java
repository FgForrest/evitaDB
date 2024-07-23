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
	// contains the prices calculated as selling price for particular query
	@PriceForSale
	@Nullable PriceContract priceForSale,

	// contains all the prices that compete for being the selling price for particular entity
	// from all those prices only the first one is used as selling price
	@PriceForSaleRef
	@Nullable PriceContract[] allPricesAvailableForSale,

	// contains price from the price list with name `basic` if it was fetched from the server
	@Price(priceList = "basic")
	@Nullable PriceContract basicPrice,

	// contains all prices of the entity that were fetched from the server
	@Price
	@NonNull Collection<PriceContract> allPrices,

	// contains all prices of the entity that were fetched from the server as list
	@Price
	@NonNull List<PriceContract> allPricesAsList,

	// contains all prices of the entity that were fetched from the server as set
	@Price
	@NonNull Set<PriceContract> allPricesAsSet,

	// contains all prices of the entity that were fetched from the server as array
	@Price
	@Nullable PriceContract[] allPricesAsArray
) {
}
