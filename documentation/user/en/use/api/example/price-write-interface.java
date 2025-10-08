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
public interface MyEntityEditor extends MyEntity {

	// creates or updates the price of the product in price list "basic"
	// the updated price is found by combination of priceId, priceList and currency
	// all necessary data are provided in the parameter value
	// if the price in parameter relates to different price list than "basic", exception is thrown
	// returns reference to self instance to allow builder pattern chaining
	@Price(priceList = "basic")
	@Nonnull MyEntityEditor setBasicPrice(@Nonnull PriceContract basicPrice);

	// analogous to the previous method, but the price is provided as a set of parameters
	@Price(priceList = "basic")
	void setBasicPrice(
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull String currencyCode,
		int priceId,
		@Nullable DateTimeRange validIn,
		@Nullable Integer innerRecordId
	);

	// analogous to the previous method, but it lacks optional parameters
	// also the currency is provided as a Currency object and not as a String
	// returns reference to self instance to allow builder pattern chaining
	@Price(priceList = "basic")
	@Nonnull MyEntityEditor setBasicPrice(
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull Currency currency,
		int priceId
	);

	// analogous to the previous method, but it accepts price list name as a parameter instead of annotation
	// returns reference to self instance to allow builder pattern chaining
	@Price
	@Nonnull MyEntityEditor setPrice(
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull String priceList,
		@Nonnull Currency currencyCode,
		int priceId,
		@Nullable DateTimeRange validIn,
		@Nullable Integer innerRecordId
	);

	// creates or updates the price of the product with price list defined in the parameter body
	// the updated price is found by combination of priceId, priceList and currency
	@Price
	void setPrice(@Nonnull PriceContract price);

	// rewrites all prices of the product
	// returns reference to self instance to allow builder pattern chaining
	@Price
	@Nonnull MyEntityEditor setAllPricesAsList(@Nonnull List<PriceContract> allPricesAsList);

	// rewrites all prices of the product using var-arg parameters
	@Price
	void setAllPricesAsArray(PriceContract... allPricesAsArray);

	// removes all prices of the product with matching external price id
	// returns reference to self instance to allow builder pattern chaining
	@Price
	@RemoveWhenExists
	@Nonnull MyEntityEditor removePricesById(int priceId);

	// removes price with matching combination of priceId, priceList and currency
	// returns the removed price (if any was found and removed)
	@Price
	@RemoveWhenExists
	@Nullable PriceContract removePriceByIdAndReturnIt(int priceId, @Nonnull String priceList, @Nonnull Currency currency);

	// removes price with matching combination of priceId, priceList and currency
	// returns the removed price external id (if any was found and removed)
	@Price
	@RemoveWhenExists
	@Nullable Integer removePriceByIdAndReturnItsId(int priceId, @Nonnull String priceList, @Nonnull Currency currency);

	// removes price with matching combination of priceId, priceList and currency
	// returns the removed price business key wrapped in a record (if any was found and removed)
	@Price
	@RemoveWhenExists
	@Nullable PriceKey removePriceByIdAndReturnItsPriceKey(int priceId, @Nonnull String priceList, @Nonnull Currency currency);

	// removes price with matching combination of priceId, priceList and currency
	// returns true if the removed price was found and removed
	@Price
	@RemoveWhenExists
	boolean removePriceByIdAndReturnTrueIfRemoved(int priceId, @Nonnull String priceList, @Nonnull Currency currency);

	// removes all prices of the product with matching price list name
	// returns reference to self instance to allow builder pattern chaining
	@Price
	@RemoveWhenExists
	@Nonnull MyEntityEditor removePricesByPriceList(@Nonnull String priceList);

	// removes all prices of the product with matching price list name
	// returns list of all removed prices or empty collection
	@Price
	@RemoveWhenExists
	@Nonnull Collection<PriceContract> removePricesByPriceListAndReturnTheirCollection(@Nonnull String priceList);

	// removes all prices of the product with matching price list name
	// returns list of all removed price external ids or empty collection
	@Price
	@RemoveWhenExists
	Collection<Integer> removePricesByPriceListAndReturnTheirIds(@Nonnull String priceList);

	// removes all prices of the product with matching price list name
	// returns list of all removed price business keys wrapped in a record or empty collection
	@Price
	@RemoveWhenExists
	Collection<PriceKey> removePricesByPriceListAndReturnTheirKeys(@Nonnull String priceList);

	// removes all prices of the product with matching currency
	@Price
	@RemoveWhenExists
	void removePricesByCurrency(@Nonnull Currency currency);

	// removes all prices of the product with matching currency
	// returns array of all removed prices or empty array (you can also use Collection instead of array)
	@Price
	@RemoveWhenExists
	@Nonnull PriceContract[] removePricesByCurrencyAndReturnTheirArray(@Nonnull Currency currency);

	// removes all prices of the product with matching currency
	// returns array of all removed price external ids or empty array (you can also use Collection instead of array)
	@Price
	@RemoveWhenExists
	@Nonnull int[] removePricesByCurrencyAndReturnArrayOfTheirIds(@Nonnull Currency currency);

	// removes all prices of the product with matching currency
	// returns array of all removed price business keys or empty array (you can also use Collection instead of array)
	@Price
	@RemoveWhenExists
	@Nonnull PriceKey[] removePricesByCurrencyAndReturnArrayOfTheirPriceKeys(@Nonnull Currency currency);

	// removes price by combination of priceId, priceList and currency
	// returns reference to self instance to allow builder pattern chaining
	@Price
	@RemoveWhenExists
	@Nonnull MyEntityEditor removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency);

	// removes price by combination of priceId and currency in price list "basic"
	// returns reference to self instance to allow builder pattern chaining
	@Price(priceList = "basic")
	@RemoveWhenExists
	@Nonnull MyEntityEditor removeBasicPrice(int priceId, @Nonnull Currency currency);

	// removes price by combination of priceId, priceList and currency
	// returns true if the price was found and removed
	@Price
	@RemoveWhenExists
	boolean removePrice(@Nonnull PriceContract price);

	// removes all prices of the entity
	// returns true if at least one price was found and removed
	@Price
	@RemoveWhenExists
	boolean removeAllPrices();

	// removes all prices of the entity
	// returns array of all removed price external ids or empty array (you can also use Collection instead of array)
	@Price
	@RemoveWhenExists
	@Nonnull int[] removeAllPricesAndReturnTheirIds();

	// removes all prices of the entity
	// returns array of all removed price business keys or empty array (you can also use Collection instead of array)
	@Price
	@RemoveWhenExists
	@Nonnull PriceKey[] removeAllPricesAndReturnTheirPriceKeys();

	// removes all prices of the entity
	// returns array of all removed prices or empty array (you can also use Collection instead of array)
	@Price
	@RemoveWhenExists
	@Nonnull PriceContract[] removeAllPricesAndReturnThem();

}
