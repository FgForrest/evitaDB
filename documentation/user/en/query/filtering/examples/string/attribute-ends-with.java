final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEndsWith("code", "solar")
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