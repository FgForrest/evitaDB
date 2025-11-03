final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityLocaleEquals(Locale.forLanguageTag("cs"))
				),
				orderBy(
					entityPrimaryKeyNatural(DESC)
				),
				require(
					entityFetch(
						attributeContent("name")
					)
				)
			)
		);
	}
);
