final EvitaResponse<SealedEntity> response = evita.queryCatalog(
	"evita",
	session -> {
		return session.query(
			query(
				collection("Product"),
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