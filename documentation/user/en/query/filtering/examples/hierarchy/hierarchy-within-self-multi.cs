EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		HierarchyWithinSelf(
        			AttributeStartsWith("code", "wire")
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