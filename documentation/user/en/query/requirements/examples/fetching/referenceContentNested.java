final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("code", "samsung-galaxy-watch-4")
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContent(
							"groups",
							entityFetch(
								attributeContent("code"),
								referenceContent(
									"tags",
									entityFetch(
										attributeContent("code"),
										referenceContent("categories")
									)
								)
							)
						)
					)
				)
			)
		);
	}
);