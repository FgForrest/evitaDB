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
					priceValidInNow(),
					userFilter(
						facetHaving(
							"brand",
							entityPrimaryKeyInSet(66465)
						),
						priceBetween(new BigDecimal("50"), new BigDecimal("400"))
					)
				),
				orderBy(
					attributeNatural("order", ASC)
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
					facetSummaryOfReference(
						"brand",
						IMPACT,
						orderBy(
							attributeNatural("name", ASC)
						),
						entityFetch(
							attributeContent("name")
						)
					),
					facetSummaryOfReference(
						"parameterValues",
						IMPACT,
						filterGroupBy(
							attributeEquals("isVisibleInFilter", true)
						),
						orderBy(
							attributeNatural("order", ASC)
						),
						orderGroupBy(
							attributeNatural("order", ASC)
						),
						entityFetch(
							attributeContent("name")
						),
						entityGroupFetch(
							attributeContent("name")
						)
					),
					priceHistogram(10, STANDARD),
					page(1, 16)
				)
			)
		);
	}
);
