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
					userFilter(
						facetHaving(
							"brand",
							entityHaving(
								attributeInSet("code", "amazon")
							)
						)
					)
				),
				require(
					facetSummaryOfReference(
						"brand",
						IMPACT,
						entityFetch(
							attributeContent("code")
						)
					)
				)
			)
		);
	}
);