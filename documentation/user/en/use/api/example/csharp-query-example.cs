EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => {
		return session.QuerySealedEntity(
			Query(
				Collection("Brand"),
				FilterBy(
					And(
						EntityPrimaryKeyInSet(1, 2, 3),
						EntityLocaleEquals(new CultureInfo("en"))
					)
				),
				OrderBy(
					AttributeNatural("name", OrderDirection.Asc)
				),
				Require(
					EntityFetchAll()
				)
			)
		);
	}
);