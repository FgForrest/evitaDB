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
							attributeEquals("code", "apple")
						)
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContent(
							"brand",
							entityFetch(
								attributeContent("code")
							)
						)
					)
				)
			)
		);
	}
);