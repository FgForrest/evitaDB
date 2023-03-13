return evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.queryListOfSealedEntities(
			query(
				collection("product"),
				require(entityFetchAll())
			)
		);
	} 
);