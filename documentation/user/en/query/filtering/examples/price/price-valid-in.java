final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "christmas-electronics")
					),
					priceInPriceLists("christmas-prices", "basic"),
					priceInCurrency(Currency.getInstance("EUR")),
					priceValidIn(
						OffsetDateTime.parse("2023-05-05T10:15:30+01:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
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