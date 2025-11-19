final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				filterBy(
					entityLocaleEquals(Locale.forLanguageTag("en")),
					attributeEquals("url", "/en/smartwatches")
				),
				require(
					entityFetch(
						attributeContent("name", "description", "descriptionShort"),
						associatedDataContent("localization")
					)
				)
			)
		);
	}
);
