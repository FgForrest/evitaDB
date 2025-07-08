final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeInSet("productType", "BASIC", "MASTER"),
					hierarchyWithinRoot(
						"categories",
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
					page(1, 10),
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);