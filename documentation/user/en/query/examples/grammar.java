final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(1, 2, 3)
				),
				orderBy(
					attributeNatural("code", DESC)
				),
				require(
					entityFetch()
				)
			)
		);
	}
);
