final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "accessories")
					)
				),
				orderBy(
					referenceProperty(
						"categories",
						traverseByEntityProperty(
							BREADTH_FIRST,
							entityPrimaryKeyNatural(ASC)
						),
						attributeNatural("orderInCategory", ASC)
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContentWithAttributes(
							"categories",
							attributeContent("orderInCategory")
						)
					)
				)
			)
		);
	}
);