final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "over-ear")
					)
				),
				require(
					hierarchyOfReference(
						"categories",
						fromRoot(
							"topLevel",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								level(1)
							)
						),
						siblings(
							"siblings",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								distance(1)
							)
						),
						parents(
							"parents",
							entityFetch(
								attributeContent("code")
							)
						)
					)
				)
			)
		);
	}
);
