final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					not(
						entityPrimaryKeyInSet(110066, 106742, 110513)
					)
				)
			)
		);
	}
);