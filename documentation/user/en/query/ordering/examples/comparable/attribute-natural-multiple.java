final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				orderBy(
					attributeNatural("ean", ASC),
					attributeNatural("catalogNumber", DESC)
				),
				require(
					entityFetch(
						attributeContent("code", "ean", "catalogNumber")
					)
				)
			)
		);
	}
);
