evita.UpdateCatalog(
	"evita", session => {
		return session.DeleteEntities(
			Query(
				Collection("Brand"),
				FilterBy(
					And(
						AttributeStartsWith("name", "A")
					)
				),
				Require(
					Page(1, 20)
				)
			)
		);
	}
);