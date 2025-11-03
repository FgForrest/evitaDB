final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("url", "/en/macbook-pro-13-2022"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						attributeContent("code", "name"),
						referenceContent(
							"categories",
							entityFetch(
								attributeContent("code", "name"),
								hierarchyContent(
									entityFetch(
										attributeContent("code", "name", "level")
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
