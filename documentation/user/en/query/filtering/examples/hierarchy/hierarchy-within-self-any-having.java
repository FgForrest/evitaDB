final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				filterBy(
					hierarchyWithinRootSelf(
						having(
							attributeEquals("status", "ACTIVE")
						),
						anyHaving(
							and(
								referenceHaving(
									"tags",
									entityHaving(
										attributeEquals("code", "HP")
									)
								),
								referenceHaving(
									"products",
									entityHaving(
										attributeEquals("status", "ACTIVE")
									)
								)
							)
						)
					)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);