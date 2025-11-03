final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("code", "macbook-pro-13-2022")
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