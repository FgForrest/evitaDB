final EvitaResponse<SealedEntity> result = session.querySealedEntity(
	query(
		// query hierarchy entity type
		collection("Product"),
		// target "True wireless" category
		filterBy(
			hierarchyWithin(
				"categories",
				attributeEquals("code", "true-wireless")
			)
		),
		require(
			hierarchyOfSelf(
				// request computation of all the parents of the "True wireless" category
				parents(
					"parentAxis",
					entityFetch(attributeContent(code)),
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
final List<LevelInfo> megaMenu = hierarchyResult.getReferenceHierarchy("categories", "parentAxis");