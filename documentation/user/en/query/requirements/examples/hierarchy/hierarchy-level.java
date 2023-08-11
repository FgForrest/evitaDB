final EvitaResponse<SealedEntity> result = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				// query hierarchy entity type
				collection("Product"),
				// target "Accessories" category
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "audio")
					)
				),
				require(
					hierarchyOfReference(
						"categories",
						// request computation top 2 levels from top
						fromRoot(
							"megaMenu",
							entityFetch(attributeContent("code")),
							stopAt(level(2))
						)
					)
				)
			)
		);
	}
);