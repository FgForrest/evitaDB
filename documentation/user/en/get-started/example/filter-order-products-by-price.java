return evita.queryCatalog(
	"evita",
	session -> {
		return session.queryListOfSealedEntities(
			query(
				collection("Product"),
				filterBy(
					and(
						priceInPriceLists("basic"),
						priceInCurrency(Currency.getInstance("EUR")),
						priceBetween(new BigDecimal(300), null),
						entityLocaleEquals(Locale.ENGLISH)
					)
				),
				orderBy(
					priceNatural(OrderDirection.ASC)
				),
				require(entityFetchAll())
			)
		);
	}
);
