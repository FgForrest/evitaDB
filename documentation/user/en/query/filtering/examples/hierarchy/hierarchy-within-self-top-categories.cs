EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		HierarchyWithinRootSelf(
        			DirectRelation()
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);