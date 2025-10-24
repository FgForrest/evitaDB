@EntityRef("Product")
public interface MyEntity {

	// returns the prices calculated as selling price for particular query
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSale
	@Nullable PriceContract getPriceForSale() throws ContextMissingException;

	// returns the prices calculated as selling price for particular query,
	// or empty if the price information was not fetched from the server or not requested in the query
	@PriceForSale
	@Nonnull Optional<PriceContract> getPriceForSaleIfPresent();

	// returns all the prices that compete for being the selling price for particular entity
	// from all those prices only the first one is used as selling price
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSaleRef
	@Nullable PriceContract[] getAllPricesAvailableForSale() throws ContextMissingException;

	// returns first price from all the prices that compete for being the selling price for particular entity
	// that match passed price list and currency, this price may not be used as selling price, because it doesn't
	// take into account the price list priority that was used in the query
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSale
	@Nullable PriceContract getPriceAvailableForSale(@Nonnull String priceList, @Nonnull Currency currency) throws ContextMissingException;

	// returns first price from all the prices that compete for being the selling price for particular entity
	// that match passed price list and currency and validity constraints, this price may not be used as selling price,
	// because it doesn't take into account the price list priority that was used in the query
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSale
	@Nullable PriceContract getPriceAvailableForSale(@Nonnull String priceList, @Nonnull Currency currency, @Nonnull OffsetDateTime validNow) throws ContextMissingException;

	// returns all the prices that compete for being the selling price for particular entity that match passed price
	// list, these prices may not be used as selling price, because they don't take into account the price list priority
	// that was used in the query
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSaleRef
	@Nullable PriceContract[] getAllPricesAvailableForSale(@Nonnull String priceList) throws ContextMissingException;

	// returns all the prices that compete for being the selling price for particular entity that match passed currency,
	// these prices may not be used as selling price, because they don't take into account the price list priority
	// that was used in the query
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSaleRef
	@Nullable PriceContract[] getAllPricesAvailableForSale(@Nonnull Currency currency) throws ContextMissingException;

	// returns all the prices that compete for being the selling price for particular entity that match passed price
	// list and currency, these prices may not be used as selling price, because they don't take into account the price
	// list priority that was used in the query
	// throws ContextMissingException if the price information was not fetched from the server
	@PriceForSaleRef
	@Nullable PriceContract[] getAllPricesAvailableForSale(@Nonnull String priceList, @Nonnull Currency currency) throws ContextMissingException;

	// returns price from the price list with name `basic` if it was fetched from the server
	// throws ContextMissingException if the price information was not fetched from the server
	@Price(priceList = "basic")
	@Nullable PriceContract getBasicPrice() throws ContextMissingException;

	// returns price from the price list with name `basic` if it was fetched from the server, or empty value
	@Price(priceList = "basic")
	@Nonnull Optional<PriceContract> getBasicPriceIfPresent();

	// returns all prices of the entity that were fetched from the server
	// throws ContextMissingException if the price information was not fetched from the server
	@Price
	@Nonnull Collection<PriceContract> getAllPrices() throws ContextMissingException;

	// returns all prices of the entity that were fetched from the server as list
	// throws ContextMissingException if the price information was not fetched from the server
	@Price
	@Nonnull List<PriceContract> getAllPricesAsList() throws ContextMissingException;

	// returns all prices of the entity that were fetched from the server as set
	// throws ContextMissingException if the price information was not fetched from the server
	@Price
	@Nonnull Set<PriceContract> getAllPricesAsSet() throws ContextMissingException;

	// returns all prices of the entity that were fetched from the server as array
	// throws ContextMissingException if the price information was not fetched from the server
	@Price
	@Nullable PriceContract[] getAllPricesAsArray() throws ContextMissingException;

}
