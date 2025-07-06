final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceValidInNow()
				),
				require(
					priceHistogram(20, STANDARD)
				)
			)
		);
	}
);