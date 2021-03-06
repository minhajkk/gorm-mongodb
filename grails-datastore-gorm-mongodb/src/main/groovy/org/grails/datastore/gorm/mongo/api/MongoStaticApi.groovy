package org.grails.datastore.gorm.mongo.api

import com.mongodb.AggregationOptions
import com.mongodb.MongoClient
import com.mongodb.ReadPreference
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.TextSearchOptions
import grails.gorm.multitenancy.Tenants
import grails.mongodb.MongoEntity
import grails.mongodb.api.MongoAllOperations
import groovy.transform.CompileStatic
import org.bson.Document
import org.bson.conversions.Bson
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.finders.FinderMethod
import org.grails.datastore.gorm.mongo.MongoCriteriaBuilder
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.engine.EntityPersister
import org.grails.datastore.mapping.engine.internal.MappingUtils
import org.grails.datastore.mapping.mongo.AbstractMongoSession
import org.grails.datastore.mapping.mongo.MongoCodecSession
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.query.MongoQuery
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.springframework.transaction.PlatformTransactionManager

/**
 * MongoDB static API implementation
 *
 * @author Graeme Rocher
 * @since 6.0
 */
@CompileStatic
class MongoStaticApi<D> extends GormStaticApi<D> implements MongoAllOperations<D> {

    MongoStaticApi(Class<D> persistentClass, Datastore datastore, List<FinderMethod> finders, PlatformTransactionManager transactionManager) {
        super(persistentClass, datastore, finders, transactionManager)
    }

    FindIterable<D> find(Bson filter) {
        withSession { AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            FindIterable<D> findIterable = session.getCollection(entity)
                                                .withDocumentClass(persistentClass)
                                                .find(filter)
            findIterable = addMultiTenantFilterIfNecessary(findIterable)
            return findIterable
        }
    }

    protected <FT> FindIterable<FT> addMultiTenantFilterIfNecessary(FindIterable<FT> findIterable) {
        if (multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            return findIterable.filter(
                    Filters.eq(MappingUtils.getTargetKey(persistentEntity.tenantId), Tenants.currentId((Class<Datastore>) datastore.getClass()))
            )
        }
        return findIterable
    }

    @Override
    MongoCriteriaBuilder createCriteria() {
        (MongoCriteriaBuilder)withSession { Session session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            return new MongoCriteriaBuilder(entity.javaClass, session)
        }
    }

    @Override
    MongoDatabase getDB() {
        (MongoDatabase)withSession({ AbstractMongoSession session ->
            def databaseName = session.getDatabase(session.mappingContext.getPersistentEntity(persistentClass.name))
            session.getNativeInterface()
                    .getDatabase(databaseName)

        })
    }

    @Override
    String getCollectionName() {
        (String)withSession({ AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            return session.getCollectionName(entity)
        })
    }

    @Override
    MongoCollection<Document> getCollection() {
        (MongoCollection<Document>)withSession { AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            return session.getCollection(entity)
        }
    }

    @Override
    def <T> T withCollection(String collectionName, Closure<T> callable) {
        withSession { AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            final previous = session.useCollection(entity, collectionName)
            try {
                def dbName = session.getDatabase(entity)
                MongoClient mongoClient = (MongoClient) session.getNativeInterface()
                MongoDatabase db = mongoClient.getDatabase(dbName)
                def coll = db.getCollection(collectionName)
                return callable.call(coll)
            } finally {
                session.useCollection(entity, previous)
            }
        }
    }

    @Override
    String useCollection(String collectionName) {
        withSession { AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            session.useCollection(entity, collectionName)
        }
    }

    @Override
    def <T> T withDatabase(String databaseName, Closure<T> callable) {
        withSession { AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            final previous = session.useDatabase(entity, databaseName)
            try {
                MongoDatabase db = session.getNativeInterface().getDatabase(databaseName)
                return callable.call(db)
            } finally {
                session.useDatabase(entity, previous)
            }
        }
    }

    @Override
    String useDatabase(String databaseName) {
        withSession { AbstractMongoSession session ->
            def entity = session.mappingContext.getPersistentEntity(persistentClass.name)
            session.useDatabase(entity, databaseName)
        }
    }

    @Override
    int countHits(String query) {
        search(query).size()
    }

    @Override
    List<D> aggregate(List pipeline, AggregationOptions options = AggregationOptions.builder().build()) {
        (List<D>)withSession( { AbstractMongoSession session ->
            def persistentEntity = session.mappingContext.getPersistentEntity(persistentClass.name)
            def mongoCollection = session.getCollection(persistentEntity)
            if(session instanceof MongoCodecSession) {
                MongoDatastore datastore = (MongoDatastore)session.getDatastore()
                mongoCollection = mongoCollection
                        .withDocumentClass(persistentEntity.javaClass)
                        .withCodecRegistry(datastore.getCodecRegistry())
            }

            List<? extends Bson> newPipeline = preparePipeline(pipeline)
            def aggregateIterable = mongoCollection.aggregate(newPipeline)
            if(options.allowDiskUse) {
                aggregateIterable.allowDiskUse(options.allowDiskUse)
            }
            if(options.batchSize) {
                aggregateIterable.batchSize(options.batchSize)
            }

            new MongoQuery.MongoResultList(aggregateIterable.iterator(), 0, (EntityPersister)session.getPersister(persistentEntity) as EntityPersister)
        } )
    }


    @Override
    List<D> aggregate(List pipeline, AggregationOptions options, ReadPreference readPreference) {
        (List<D>)withSession( { AbstractMongoSession session ->
            def persistentEntity = session.mappingContext.getPersistentEntity(persistentClass.name)
            List<? extends Bson> newPipeline = preparePipeline(pipeline)
            def mongoCollection = session.getCollection(persistentEntity)
                    .withReadPreference(readPreference)
            def aggregateIterable = mongoCollection.aggregate(newPipeline)
            aggregateIterable.allowDiskUse(options.allowDiskUse)
            aggregateIterable.batchSize(options.batchSize)

            new MongoQuery.MongoResultList(aggregateIterable.iterator(), 0, (EntityPersister)session.getPersister(persistentEntity))
        } )
    }

    @Override
    List<D> search(String query, Map options = Collections.emptyMap()) {
        (List<D>)withSession( { AbstractMongoSession session ->
            def persistentEntity = session.mappingContext.getPersistentEntity(persistentClass.name)
            def coll = session.getCollection(persistentEntity)
            if(session instanceof MongoCodecSession) {
                MongoDatastore datastore = (MongoDatastore)session.datastore
                coll = coll
                        .withDocumentClass(persistentEntity.javaClass)
                        .withCodecRegistry(datastore.codecRegistry)
            }
            Bson search
            if(options.language) {
                search = Filters.text(query, new TextSearchOptions().language(options.language.toString()))
            }
            else {
                search = Filters.text(query)
            }

            FindIterable cursor = coll.find(search)

            int offset = options.offset instanceof Number ? ((Number)options.offset).intValue() : 0
            int max = options.max instanceof Number ? ((Number)options.max).intValue() : -1
            if(offset > 0) cursor.skip(offset)
            if(max > -1) cursor.limit(max)
            cursor = addMultiTenantFilterIfNecessary(cursor)
            new MongoQuery.MongoResultList(cursor.iterator(), offset, (EntityPersister)session.getPersister(persistentEntity))
        } )
    }

    @Override
    List<D> searchTop(String query, int limit = 5, Map options = Collections.emptyMap()) {
        (List<D>)withSession( { AbstractMongoSession session ->
            def persistentEntity = session.mappingContext.getPersistentEntity(persistentClass.name)

            MongoCollection coll = session.getCollection(persistentEntity)
            if(session instanceof MongoCodecSession) {
                MongoDatastore datastore = (MongoDatastore)session.datastore
                coll = coll
                        .withDocumentClass(persistentEntity.javaClass)
                        .withCodecRegistry(datastore.codecRegistry)
            }
            EntityPersister persister = (EntityPersister)session.getPersister(persistentEntity)

            Bson search
            if(options.language) {
                search = Filters.text(query, new TextSearchOptions().language(options.language.toString()))
            }
            else {
                search = Filters.text(query)
            }


            def score = Projections.metaTextScore("score")
            FindIterable cursor = coll.find(search)
                                            .projection(score)
                                            .sort(score)
                                            .limit(limit)

            cursor = addMultiTenantFilterIfNecessary(cursor)
            new MongoQuery.MongoResultList(cursor.iterator(), 0, persister)
        } )
    }

    @Override
    @Deprecated
    Document getDbo(D instance) {
        return ((MongoEntity)instance).dbo
    }


    @CompileStatic
    private List<Bson> preparePipeline(List pipeline) {
        List<Bson> newPipeline = new ArrayList<Bson>()
        if (multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            newPipeline.add(
                    Filters.eq(MappingUtils.getTargetKey(persistentEntity.tenantId), Tenants.currentId((Class<Datastore>) datastore.getClass()))
            )
        }
        for (o in pipeline) {
            if (o instanceof Bson) {
                newPipeline << (Bson)o
            } else if (o instanceof Map) {
                newPipeline << new Document((Map) o)
            }
        }
        newPipeline
    }
}
