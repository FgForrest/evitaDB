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
				// request computation of direct children of the category
				fromRoot(
					"megaMenu",
					entityFetch(attributeContent(code)),
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
final List<LevelInfo> megaMenu = hierarchyResult.getSelfHierarchy("megaMenu");