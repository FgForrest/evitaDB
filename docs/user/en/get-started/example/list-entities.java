return evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.queryListOfSealedEntities(
			query(
				collection("Product"),
				require(entityFetchAll())
			)
		);
	} 
);