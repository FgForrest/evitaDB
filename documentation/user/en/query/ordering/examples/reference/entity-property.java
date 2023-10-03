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
								entityProperty(
									attributeNatural("name", DESC)
								)
							),
							entityFetch(
								attributeContent("name")
							)
						)
					)
				)
			)
		);
	}
);