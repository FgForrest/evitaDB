final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.query(
			query(
				collection("product"),
				filterBy(
					and(
						entityPrimaryKeyInSet(1),
						entityLocaleEquals(Locale.ENGLISH),
						priceInPriceLists("basic"),
						priceInCurrency(Currency.getInstance("CZK"))
					)
				),
				require(
					entityFetch(
						attributeContent("name"),
						associatedDataContent(),
						priceContent(),
						referenceContent("brand")
					)
				)
			),
			SealedEntity.class
		);
	}
);