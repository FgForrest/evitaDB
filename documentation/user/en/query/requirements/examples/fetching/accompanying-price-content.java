final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("code", "iget-blackview-tab-g11"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceInPriceLists("basic")
				),
				require(
					defaultAccompanyingPriceLists("reference"),
					entityFetch(
						priceContentRespectingFilter(),
						accompanyingPriceContentDefault(),
						accompanyingPriceContent("special", "employee-basic-price", "b2b-basic-price")
					)
				)
			)
		);
	}
);