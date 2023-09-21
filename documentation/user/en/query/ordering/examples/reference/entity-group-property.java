final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("code", "garmin-vivoactive-4"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContent(
							"parameterValues",
							orderBy(
								entityGroupProperty(
									attributeNatural("name", ASC)
								),
								entityProperty(
									attributeNatural("name", ASC)
								)
							),
							entityFetch(
								attributeContent("name")
							),
							entityGroupFetch(
								attributeContent("name")
							)
						)
					)
				)
			)
		);
	}
);