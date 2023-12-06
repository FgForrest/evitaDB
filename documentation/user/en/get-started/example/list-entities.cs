return evita.QueryCatalog(
	"testCatalog",
	session => {
		return session.QueryListOfSealedEntities(
			Query(
				Collection("Product"),
				FilterBy(
					EntityLocaleEquals(new CultureInfo("en"))
				),
				Require(EntityFetchAll())
			)
		);
	} 
);