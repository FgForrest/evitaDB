final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(103885),
					priceInCurrency(Currency.getInstance("EUR")),
					priceInPriceLists("employee-basic-price", "basic")
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