final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					entityPrimaryKeyInSet(105703)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContentAllWithAttributes()
					)
				)
			)
		);
	}
);