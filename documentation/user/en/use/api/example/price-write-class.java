@EntityRef("Product")
@Data
public class MyEntityEditor {

	// contains the prices calculated as selling price for particular query
	@PriceForSale
	@Nullable private PriceContract priceForSale;

	// contains all the prices that compete for being the selling price for particular entity
	// from all those prices only the first one is used as selling price
	@PriceForSaleRef
	@Nullable private PriceContract[] allPricesAvailableForSale;

	// contains price from the price list with name `basic` if it was fetched from the server
	@Price(priceList = "basic")
	@Nullable private PriceContract basicPrice;

	// contains all prices of the entity that were fetched from the server
	@Price
	@Nonnull private Collection<PriceContract> allPrices;

	// contains all prices of the entity that were fetched from the server as list
	@Price
	@Nonnull private List<PriceContract> allPricesAsList;

	// contains all prices of the entity that were fetched from the server as set
	@Price
	@Nonnull private Set<PriceContract> allPricesAsSet;

	// contains all prices of the entity that were fetched from the server as array
	@Price
	@Nullable private PriceContract[] allPricesAsArray;

}
