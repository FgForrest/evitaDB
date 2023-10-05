final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					referenceHaving(
						"brand",
						entityPrimaryKeyInSet(66465)
					)
				),
				require(
					entityFetch(
						attributeContent("code"),
						referenceContentWithAttributes("brand")
					)
				)
			)
		);
	}
);