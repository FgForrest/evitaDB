final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					hierarchyOfReference(
						"categories",
						fromNode(
							"dynamicMenuSubcategories",
							node(
								filterBy(
									entityPrimaryKeyInSet(66482)
								)
							),
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								distance(1)
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
