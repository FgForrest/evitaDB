final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					userFilter(
						facetHaving(
							"parameterValues",
							entityHaving(
								attributeInSet("code", "ram-memory-64")
							)
						)
					)
				),
				require(
					facetSummaryOfReference(
						"parameterValues",
						IMPACT,
						filterBy(
							attributeContains("code", "4")
						),
						filterGroupBy(
							attributeInSet("code", "ram-memory", "rom-memory")
						),
						entityFetch(
							attributeContent("code")
						),
						entityGroupFetch(
							attributeContent("code")
						)
					),
					facetGroupsDisjunction(
						"parameterValues",
						WITH_DIFFERENT_GROUPS,
						filterBy(
							attributeInSet("code", "ram-memory", "rom-memory")
						)
					)
				)
			)
		);
	}
);
