final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					priceInPriceLists("b2b-basic-price"),
					priceInCurrency(Currency.getInstance("EUR"))
				),
				orderBy(
					priceDiscount("basic")
				),
				require(
					entityFetch(
						priceContentRespectingFilter("basic")
					)
				)
			)
		);
	}
);