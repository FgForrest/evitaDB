final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.query(
			query(
				collection(Entities.BRAND),
				filterBy(
					and(
						entityPrimaryKeyInSet(1, 2, 3),
						locale != null ? entityLocaleEquals(Locale.ENGLISH) : null
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