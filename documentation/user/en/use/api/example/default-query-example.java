final EvitaResponse<EntityReference> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.query(
			query(
				collection("Product"),
				filterBy(
					and(
						entityPrimaryKeyInSet(1),
						entityLocaleEquals(Locale.ENGLISH),
						priceInPriceLists("basic"),
						priceInCurrency(Currency.getInstance("CZK"))
					)
				)
			),
			EntityReference.class
		);
	}
);