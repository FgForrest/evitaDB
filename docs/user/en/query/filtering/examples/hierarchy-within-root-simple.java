
final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				filterBy(
					hierarchyWithinRootSelf()
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