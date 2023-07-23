final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(63049)
				),
				require(
					entityFetch(
						attributeContentAll()
					)
				)
			)
		);
	}
);