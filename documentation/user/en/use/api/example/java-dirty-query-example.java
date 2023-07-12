// method nullable parameter
var locale = Locale.ENGLISH;
// is used in a query
final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.query(
			query(
				collection("Brand"),
				filterBy(
					and(
						entityPrimaryKeyInSet(1, 2, 3),
						locale != null ? entityLocaleEquals(locale) : null
					)
				),
				orderBy(
					attributeNatural("name", OrderDirection.ASC)
				),
				require(
					entityFetchAll()
				)
			),
			SealedEntity.class
		);
	}
);