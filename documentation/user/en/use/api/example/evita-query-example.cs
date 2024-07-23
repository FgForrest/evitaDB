EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => {
		return session.QuerySealedEntity(
			Query(
				Collection("Product"),
				FilterBy(
					And(
						HierarchyWithin(
							"categories",
							AttributeEquals("url", "/local-food")
						),
						EntityLocaleEquals(new CultureInfo("cs")),
						PriceValidInNow(),
						PriceInCurrency(new Currency("CZK")),
						PriceInPriceLists("vip", "loyal-customer", "regular-prices"),
						UserFilter(
							FacetHaving(
								"parameterValues",
								EntityHaving(
									AttributeInSet("code", "gluten-free", "original-recipe")
								)
							),
							PriceBetween(600m, 1600m)
						)
					)
				),
				Require(
					Page(1, 20),
					FacetSummary(Impact),
					PriceType(WithTax),
					PriceHistogram(30)
				)
			)
		);
	}
);