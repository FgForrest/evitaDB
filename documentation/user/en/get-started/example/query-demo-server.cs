var entities = evita.QueryCatalog(
	"evita",
	session => {
		return session.QueryListOfSealedEntities(
			Query(
				Collection("Brand"),
				FilterBy(
					And(
						AttributeStartsWith("name", "A"),
						EntityLocaleEquals(new CultureInfo("en"))
					)
				),
				OrderBy(
					AttributeNatural("name", OrderDirection.Asc)
				),
				Require(EntityFetchAll())
			)
		);
	}
)