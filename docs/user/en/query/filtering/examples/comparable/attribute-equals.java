final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeEquals("code", "apple-iphone-13-pro-3")
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