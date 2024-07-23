return evita.QueryCatalog(
	"evita",
	session => {
		return session.QueryListOfSealedEntities(
			Query(
				Collection("Product"),
				FilterBy(
					And(
						PriceInPriceLists("basic"),
						PriceInCurrency(new Currency("EUR")),
						PriceBetween(300m, null),
						EntityLocaleEquals(new CultureInfo("en"))
					)
				),
				OrderBy(
					PriceNatural(OrderDirection.Asc)
				),
				Require(EntityFetchAll())
			)
		);
	}
);