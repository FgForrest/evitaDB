evita.updateCatalog(
	"evita", session -> {
		return session.deleteEntities(
			query(
				collection("Brand"),
				filterBy(
					and(
						attributeStartsWith("name", "A")
					)
				),
				require(
					page(1, 20)
				)
			)
		);
	}
);
