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
						// request computation of all the parents of the Audio category
						parents(
							"parent",
							entityFetch(attributeContent("code")),
							stopAt(distance(1)),
							statistics(
								CHILDREN_COUNT,
								QUERIED_ENTITY_COUNT
							)
						)
					)
				)
			)
		);
	}
);