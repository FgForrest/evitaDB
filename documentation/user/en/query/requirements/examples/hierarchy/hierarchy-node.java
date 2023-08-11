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
						attributeEquals("code", "accessories")
					)
				),
				require(
					hierarchyOfReference(
						"categories",
						// request computation of children of the Accessories category
						// but stop at nodes starting with letter `w`
						children(
							"subMenu",
							entityFetch(attributeContent("code")),
							stopAt(
								node(
									filterBy(
										attributeStartsWith("code", "w")
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

final Hierarchy hierarchyResult = result.getExtraResult(Hierarchy.class);
final List<LevelInfo> subMenu = hierarchyResult.getReferenceHierarchy("categories", "subMenu");