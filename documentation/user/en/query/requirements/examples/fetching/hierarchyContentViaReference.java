final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("code", "amazfit-gtr-3"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						referenceContent(
							"categories",
							entityFetch(
								attributeContent("code", "name"),
								hierarchyContent(
									entityFetch(
										attributeContent("code", "name")
									)
								)
							)
						)
					)
				)
			)
		);
	}
);