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
					attributeEquals("status", "ACTIVE")
				),
				require(
					facetSummary(
						entityFetch(
							attributeContent("code")
						),
						entityGroupFetch(
							attributeContent("code")
						)
					)
				)
			)
		);
	}
);