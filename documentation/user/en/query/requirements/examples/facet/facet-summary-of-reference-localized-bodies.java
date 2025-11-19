final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "e-readers")
					),
					entityLocaleEquals(Locale.forLanguageTag("en")),
					attributeEquals("status", "ACTIVE")
				),
				require(
					facetSummaryOfReference(
						"parameterValues",
						entityFetch(
							attributeContent("name")
						),
						entityGroupFetch(
							attributeContent("name")
						)
					)
				)
			)
		);
	}
);