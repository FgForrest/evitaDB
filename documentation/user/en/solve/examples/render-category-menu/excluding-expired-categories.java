final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "accessories"),
						having(
							or(
								attributeIs("validity", NULL),
								attributeInRangeNow("validity")
							)
						)
					)
				),
				require(
					hierarchyOfReference(
						"categories",
						fromRoot(
							"topLevel",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								level(2)
							)
						)
					)
				)
			)
		);
	}
);
