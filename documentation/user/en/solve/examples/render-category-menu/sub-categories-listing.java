final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityLocaleEquals(Locale.forLanguageTag("en")),
					hierarchyWithin(
						"categories",
						attributeEquals("url", "/en/accessories")
					)
				),
				require(
					hierarchyOfReference(
						"categories",
						children(
							"subcategories",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								distance(1)
							)
						)
					),
					page(1, 0)
				)
			)
		);
	}
);
