final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeIs("catalogNumber", NULL)
				),
				require(
					entityFetch(
						attributeContent("code", "catalogNumber")
					)
				)
			)
		);
	}
);