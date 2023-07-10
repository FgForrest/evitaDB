final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeContains("code", "epix")
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