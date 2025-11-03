final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				orderBy(
					attributeNatural("orderedQuantity", DESC)
				),
				require(
					entityFetch(
						attributeContent("code", "orderedQuantity")
					)
				)
			)
		);
	}
);
