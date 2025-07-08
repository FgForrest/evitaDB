final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(103885),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						attributeContent("name"),
						referenceContent(
							"parameterValues",
							orderBy(
								entityProperty(
									attributeNatural("order", ASC)
								)
							),
							entityFetch(
								attributeContent("name")
							),
							page(1, 3)
						)
					)
				)
			)
		);
	}
);