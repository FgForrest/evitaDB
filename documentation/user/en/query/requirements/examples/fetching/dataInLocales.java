final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Brand"),
				filterBy(
					entityPrimaryKeyInSet(64703)
				),
				require(
					entityFetch(
						attributeContent("code", "name"),
						dataInLocales(Locale.forLanguageTag("cs"))
					)
				)
			)
		);
	}
);