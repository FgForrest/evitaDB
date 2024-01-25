return evita.QueryCatalog(
	"evita",
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