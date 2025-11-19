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
					),
					userFilter(
						facetHaving(
							"brand",
							entityPrimaryKeyInSet(66465)
						)
					)
				),
				require(
					facetSummaryOfReference(
						"brand",
						IMPACT,
						orderBy(
							attributeNatural("name", ASC)
						),
						entityFetch(
							attributeContent("name")
						)
					)
				)
			)
		);
	}
);
