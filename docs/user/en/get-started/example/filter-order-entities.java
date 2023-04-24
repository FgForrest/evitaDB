return evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.queryListOfSealedEntities(
			query(
				collection("brand"),
				filterBy(
					and(
						attributeStartsWith("name", "A"),
						entityLocaleEquals(Locale.ENGLISH)
					)
				),
				orderBy(
					attributeNatural("name", OrderDirection.ASC)
				),
				require(entityFetchAll())
			)
		);
	}
);