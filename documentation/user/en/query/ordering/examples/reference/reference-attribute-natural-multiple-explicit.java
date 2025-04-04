final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "e-readers")
					)
				),
				orderBy(
					referenceProperty(
						"categories",
						pickFirstByEntityProperty(
							attributeNatural("order", DESC)
						),
						attributeNatural("categoryPriority", DESC)
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContentWithAttributes(
							"categories",
							attributeContent("categoryPriority")
						)
					)
				)
			)
		);
	}
);