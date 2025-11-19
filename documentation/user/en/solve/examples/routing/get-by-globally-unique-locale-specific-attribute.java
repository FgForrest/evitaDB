final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				filterBy(
					attributeEquals("url", "/en/macbook-pro-13-2022"),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						attributeContentAll()
					)
				)
			)
		);
	}
);