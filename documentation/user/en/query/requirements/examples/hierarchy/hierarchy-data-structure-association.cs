EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		HierarchyWithinSelf(
        			AttributeEquals("code", "audio")
        		)
        	),
        	Require(
        		HierarchyOfSelf(
        			Children(
        				"directChildren",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				)
        			),
        			Parents(
        				"directParent",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				)
        			)
        		)
        	)
        )
	)
);