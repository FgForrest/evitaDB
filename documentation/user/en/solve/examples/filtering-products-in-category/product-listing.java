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
					attributeEquals("status", "ACTIVE"),
					or(
						attributeInRangeNow("validity"),
						attributeIs("validity", NULL)
					),
					referenceHaving(
						"stocks",
						attributeGreaterThan("quantityOnStock", 0)
					),
					priceInCurrency(Currency.getInstance("EUR")),
					priceInPriceLists("basic"),
					priceValidInNow()
				),
				require(
					entityFetch(
						attributeContent("name"),
						referenceContentWithAttributes(
							"stocks",
							attributeContent("quantityOnStock")
						),
						priceContentRespectingFilter("reference")
					),
					page(1, 16)
				)
			)
		);
	}
);
