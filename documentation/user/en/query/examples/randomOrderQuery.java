final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				orderBy(
					attributeNatural("code", ASC)
				),
				require(
					entityFetch()
				)
			)
		);
	}
);