final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(110513, 66567, 106742, 66574, 66556, 110066),
					not(
						entityPrimaryKeyInSet(110066, 106742, 110513)
					)
				)
			)
		);
	}
);