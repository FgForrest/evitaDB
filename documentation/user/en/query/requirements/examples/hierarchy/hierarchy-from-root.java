final EvitaResponse<SealedEntity> result = session.querySealedEntity(
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
				// request computation top 2 level category tree
				fromRoot(
					"megaMenu",
					entityFetch(attributeContent("code")),
					stopAt(level(2)),
					statistics(
						CHILDREN_COUNT,
						QUERIED_ENTITY_COUNT
					)
				)
			)
		)
	)
);

final Hierarchy hierarchyResult = result.getExtraResult(Hierarchy.class);
// mega menu listing
final List<LevelInfo> megaMenu = hierarchyResult.getReferenceHierarchy("categories", "megaMenu");