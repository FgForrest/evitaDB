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
						// request computation of direct children of the Portables category
						fromNode(
							"sideMenu1",
							node(
								filterBy(
									attributeEquals("code", "portables")
								)
							),
							entityFetch(attributeContent("code")),
							stopAt(distance(1)),
							statistics(
								CHILDREN_COUNT,
								QUERIED_ENTITY_COUNT
							)
						),
						// request computation of direct children of the Laptops category
						fromNode(
							"sideMenu2",
							node(
								filterBy(
									attributeEquals("code", "laptops")
								)
							),
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

final Hierarchy hierarchyResult = result.getExtraResult(Hierarchy.class);
// index of both menu components
final Map<String, List<LevelInfo>> megaMenu = hierarchyResult.getReferenceHierarchy("categories");