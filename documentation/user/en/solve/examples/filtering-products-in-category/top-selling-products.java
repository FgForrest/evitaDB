final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityLocaleEquals(Locale.forLanguageTag("en")),
					hierarchyWithin(
						"categories",
						attributeEquals("url", "/en/smartwatches")
					)
				),
				orderBy(
					attributeNatural("orderedQuantity", DESC)
				),
				require(
					entityFetch(
						attributeContent("name")
					),
					page(1, 5)
				)
			)
		);
	}
);
