evita.UpdateCatalog(
	"evita", session => {
		return session.DeleteEntities(
			Query(
				Collection("Brand"),
				FilterBy(EntityPrimaryKeyInSet(1, 2))
			)
		);
	}
);