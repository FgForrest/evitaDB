final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.query(
			query(
				collection("Brand"),
				filterBy(
					and(
						entityPrimaryKeyInSet(1, 2, 3),
						entityLocaleEquals(Locale.ENGLISH)
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