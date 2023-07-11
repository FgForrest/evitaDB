final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					referenceHaving(
						"brand",
						entityHaving(
							attributeInSet("code", "sony", "google")
						)
					)
				),
				orderBy(
					referenceProperty(
						"brand",
						attributeNatural("orderInBrand", ASC)
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContentWithAttributes(
							"brand",
							attributeContent("orderInBrand")
						)
					)
				)
			)
		);
	}
);