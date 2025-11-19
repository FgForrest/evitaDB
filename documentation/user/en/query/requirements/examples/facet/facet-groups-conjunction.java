final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					userFilter(
						facetHaving(
							"groups",
							entityHaving(
								attributeInSet("code", "sale")
							)
						)
					)
				),
				require(
					facetSummaryOfReference(
						"groups",
						IMPACT,
						entityFetch(
							attributeContent("code")
						)
					),
					facetGroupsConjunction("groups", WITH_DIFFERENT_FACETS_IN_GROUP)
				)
			)
		);
	}
);