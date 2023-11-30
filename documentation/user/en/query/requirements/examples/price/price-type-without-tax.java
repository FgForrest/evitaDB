final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceBetween(new BigDecimal("100"), new BigDecimal("103"))
				),
				require(
					priceType(WITHOUT_TAX),
					entityFetch(
						attributeContent("code"),
						priceContentRespectingFilter()
					)
				)
			)
		);
	}
);