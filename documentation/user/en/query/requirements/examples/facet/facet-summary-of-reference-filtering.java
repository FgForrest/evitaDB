final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("status", "ACTIVE")
				),
				require(
					facetSummaryOfReference(
						"parameterValues",
						filterBy(
							attributeContains("code", "ar")
						),
						filterGroupBy(
							attributeStartsWith("code", "o")
						),
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