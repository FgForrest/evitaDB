final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Brand"),
				filterBy(
					entityPrimaryKeyInSet(64703),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				require(
					entityFetch(
						associatedDataContent("allActiveUrls", "localization")
					)
				)
			)
		);
	}
);