final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				filterBy(
					attributeEquals("url", "/en/smartwatches"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
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
		);
	}
);
