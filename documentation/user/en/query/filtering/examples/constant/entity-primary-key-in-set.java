final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(110066, 106742, 107546, 110513)
				),
				require(
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);