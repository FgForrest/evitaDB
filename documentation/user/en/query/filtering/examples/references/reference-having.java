final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					referenceHaving(
						"relatedProducts",
						attributeEquals("category", "alternativeProduct")
					)
				),
				require(
					entityFetch(
						referenceContentWithAttributes(
							"relatedProducts",
							attributeContent("category")
						)
					)
				)
			)
		);
	}
);