
final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithinRoot("categories")
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