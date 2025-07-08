final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					scope(LIVE, ARCHIVED),
					entityLocaleEquals(Locale.forLanguageTag("en")),
					inScope(
						LIVE,
						hierarchyWithin(
							"categories",
							attributeEquals("url", "/en/cell-phones")
						),
						priceInPriceLists("basic"),
						priceInCurrency(Currency.getInstance("EUR")),
						priceValidInNow()
					)
				),
				require(
					inScope(
						LIVE,
						priceHistogram(5, STANDARD)
					)
				)
			)
		);
	}
);