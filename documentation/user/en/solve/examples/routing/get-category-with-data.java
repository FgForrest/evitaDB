final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				filterBy(
					attributeEquals("url", "/en/wireless-headphones"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						attributeContent("code", "availability", "brandCode", "level"),
						associatedDataContentAll(),
						referenceContentAll(),
						hierarchyContent()
					)
				)
			)
		);
	}
);