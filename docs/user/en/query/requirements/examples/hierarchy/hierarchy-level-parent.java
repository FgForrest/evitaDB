final EvitaResponse<SealedEntity> result = session.querySealedEntity(
	query(
		// query hierarchy entity type
		collection("Product"),
		// target "Accessories" category
		filterBy(
			hierarchyWithin(
				"categories",
				attributeEquals("code", "true-wireless")
			)
		),
		require(
			hierarchyOfReference(
				"categories",
				// request computation of all parents
				// until level 2 parent is reached
				parents(
					"parents",
					entityFetch(attributeContent("code")),
					stopAt(level(2))
				)
			)
		)
	)
);