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
					attributeEquals("status", "ACTIVE"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					facetSummaryOfReference(
						"brand",
						orderBy(
							attributeNatural("name", ASC)
						),
						entityFetch(
							attributeContent("name")
						)
					),
					facetSummaryOfReference(
						"parameterValues",
						filterGroupBy(
							attributeEquals("isVisibleInFilter", true)
						),
						orderBy(
							attributeNatural("order", ASC)
						),
						orderGroupBy(
							attributeNatural("order", ASC)
						),
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
