EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(110513, 66567, 106742, 66574, 66556, 110066),
        		Not(
        			EntityPrimaryKeyInSet(110066, 106742, 110513)
        		)
        	)
        )
	)
);