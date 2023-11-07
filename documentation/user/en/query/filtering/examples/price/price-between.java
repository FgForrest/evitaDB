final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "e-readers")
					),
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					userFilter(
						priceBetween(new BigDecimal("150.0"), new BigDecimal("170.5"))
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						priceContentRespectingFilter()
					)
				)
			)
		);
	}
);