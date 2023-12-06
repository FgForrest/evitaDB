return evita.QueryCatalog(
	"testCatalog",
	session => {
		return session.QueryListOfSealedEntities(
			Query(
				Collection("Brand"),
				FilterBy(
					AttributeStartsWith("name", "A")
				),
				OrderBy(
					AttributeNatural("name", OrderDirection.Asc)
				),
				Require(EntityFetchAll())
			)
		);
	}
);