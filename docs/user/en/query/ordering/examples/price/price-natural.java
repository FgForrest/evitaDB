final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					priceInPriceLists("basic"),
					priceInCurrency(Currency.getInstance("CZK"))
				),
				orderBy(
					priceNatural(DESC)
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);
	}
);