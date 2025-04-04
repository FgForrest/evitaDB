final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					referenceHaving(
						"brand",
						entityHaving(
							attributeEquals("code", "asus")
						)
					),
					userFilter(
						facetHaving(
							"categories",
							entityHaving(
								attributeEquals("code", "laptops")
							),
							includingChildren()
						)
					)
				),
				require(
					facetSummaryOfReference(
						"categories",
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
