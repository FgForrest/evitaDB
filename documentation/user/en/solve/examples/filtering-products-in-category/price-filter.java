final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityLocaleEquals(Locale.forLanguageTag("en")),
					hierarchyWithin(
						"categories",
						attributeEquals("url", "/en/smartwatches")
					),
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceValidInNow(),
					userFilter(
						priceBetween(new BigDecimal("50"), new BigDecimal("400"))
					)
				),
				require(
					priceHistogram(10, STANDARD)
				)
			)
		);
	}
);
