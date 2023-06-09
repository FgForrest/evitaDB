return evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.queryListOfSealedEntities(
			query(
				collection("Product"),
				filterBy(
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(entityFetchAll())
			)
		);
	} 
);