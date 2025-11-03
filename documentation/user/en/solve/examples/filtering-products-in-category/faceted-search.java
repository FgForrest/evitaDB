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
				require(
					facetSummaryOfReference(
						"parameterValues",
						IMPACT,
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
