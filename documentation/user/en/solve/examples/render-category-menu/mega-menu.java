final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				require(
					hierarchyOfReference(
						"categories",
						fromRoot(
							"megaMenu",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								level(2)
							)
						)
					),
					page(1, 0)
				)
			)
		);
	}
);
