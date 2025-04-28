final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					and(
						hierarchyWithin(
							"categories",
							attributeEquals("url", "/local-food")
						),
						entityLocaleEquals(Locale.forLanguageTag("cs")),
						priceValidInNow(),
						priceInCurrency(Currency.getInstance("CZK")),
						priceInPriceLists("vip", "loyal-customer", "regular-prices"),
						userFilter(
							facetHaving(
								"parameterValues",
								entityHaving(
									attributeInSet("code", "gluten-free", "original-recipe")
								)
							),
							priceBetween(new BigDecimal("600"), new BigDecimal("1600"))
						)
					)
				),
				require(
					page(1, 20),
					facetSummary(IMPACT),
					priceType(WITH_TAX),
					priceHistogram(30, STANDARD)
				)
			)
		);
	}
);