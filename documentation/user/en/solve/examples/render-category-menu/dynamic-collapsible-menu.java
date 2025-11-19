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
							"dynamicMenu",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								level(1)
							),
							statistics(WITHOUT_USER_FILTER, CHILDREN_COUNT)
						)
					),
					page(1, 0)
				)
			)
		);
	}
);
