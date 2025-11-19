final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					hierarchyOfReference(
						"categories",
						fromRoot(
							"megaMenu",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								level(2)
							),
							statistics(WITHOUT_USER_FILTER, QUERIED_ENTITY_COUNT)
						)
					),
					page(1, 0)
				)
			)
		);
	}
);
