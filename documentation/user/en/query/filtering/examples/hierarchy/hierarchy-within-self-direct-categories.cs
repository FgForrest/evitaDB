EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		HierarchyWithinSelf(
        			AttributeEquals("code", "accessories"),
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