final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					and(
						entityPrimaryKeyInSet(1),
						entityLocaleEquals(Locale.forLanguageTag("en")),
						priceInPriceLists("basic"),
						priceInCurrency(Currency.getInstance("CZK"))
					)
				),
				require(
					entityFetch(
						attributeContent("name"),
						associatedDataContentAll(),
						priceContentRespectingFilter(),
						referenceContent("brand")
					)
				)
			)
		);
	}
);