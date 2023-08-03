final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Category"),
				filterBy(
					attributeEquals("code", "smartwatches")
				),
				require(
					entityFetch(
						hierarchyContent()
					)
				)
			)
		);
	}
);