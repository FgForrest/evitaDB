final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeStartsWith("code", "lenovo")
				),
				orderBy(
					entityPrimaryKeyExact(104732, 104718, 105929)
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