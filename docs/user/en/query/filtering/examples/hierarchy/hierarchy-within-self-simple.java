final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				collection(),
				filterBy(
					hierarchyWithinSelf(
						attributeEquals("code", "accessories")
					)
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