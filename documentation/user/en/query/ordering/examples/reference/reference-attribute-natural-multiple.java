final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					referenceHaving(
						"groups",
						entityHaving(
							attributeInSet("code", "sale", "new")
						)
					)
				),
				orderBy(
					referenceProperty(
						"groups",
						attributeNatural("orderInGroup", ASC)
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContentWithAttributes(
							"groups",
							attributeContent("orderInGroup")
						)
					)
				)
			)
		);
	}
);
