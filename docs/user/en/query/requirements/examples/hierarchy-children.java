final EvitaResponse<SealedEntity> result = session.querySealedEntity(
	query(
		// query hierarchy entity type
		collection("Product"),
		// target "Accessories" category
		filterBy(
			hierarchyWithinReference(
				"categories",
				attributeEquals("code", "audio")
			)
		),
		require(
			hierarchyOfSelf(
				// request computation top 2 level category tree
				children(
					"subcategories",
					entityFetch(attributeContent(code)),
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

final Hierarchy hierarchyResult = result.getExtraResult(Hierarchy.class);
// mega menu listing
final List<LevelInfo> megaMenu = hierarchyResult.getReferenceHierarchy("categories", "subcategories");