// method nullable parameter
var locale = new CultureInfo("en");
// is used in a query
EvitaResponse<EvitaEntityResponse, ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => {
		return session.Query<ISealedEntity>(
			Query(
				Collection("Brand"),
				FilterBy(
					And(
						EntityPrimaryKeyInSet(1, 2, 3),
						locale is not null ? EntityLocaleEquals(locale) : null
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