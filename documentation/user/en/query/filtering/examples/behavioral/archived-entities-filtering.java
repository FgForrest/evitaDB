final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					scope(LIVE, ARCHIVED),
					attributeInSet("url", "/en/xiaomi-redmi-note-10-pro-8", "/en/apple-iphone-14"),
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
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);