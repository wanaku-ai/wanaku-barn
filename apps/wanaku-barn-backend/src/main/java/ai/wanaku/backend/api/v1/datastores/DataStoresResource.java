package ai.wanaku.backend.api.v1.datastores;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import ai.wanaku.capabilities.sdk.api.exceptions.DataStoreResourceNotFoundException;
import ai.wanaku.capabilities.sdk.api.exceptions.WanakuException;
import ai.wanaku.capabilities.sdk.api.types.DataStore;
import ai.wanaku.capabilities.sdk.api.types.WanakuResponse;
import ai.wanaku.core.services.api.DataStoreRecord;
import ai.wanaku.core.util.StringHelper;

/**
 * REST API resource for managing DataStore entries.
 * Base path: /api/v1/data-store
 */
@ApplicationScoped
@Path("/api/v1/data-store")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataStoresResource {
    private static final Logger LOG = Logger.getLogger(DataStoresResource.class);

    @Inject
    DataStoresBean dataStoresBean;

    /**
     * Add a new data store entry.
     * POST /api/v1/data-store
     *
     * @param dataStore the data store to add
     * @return response with the created data store and its revision in the {@code ETag} header
     */
    @POST
    public RestResponse<WanakuResponse<DataStoreRecord>> add(DataStore dataStore) {
        LOG.debugf("REST: Adding data store: %s", dataStore);
        DataStoreRecord result = record(dataStoresBean.add(dataStore));
        return withEntityTag(new WanakuResponse<>(result), result);
    }

    /**
     * Update an existing data store entry.
     * PUT /api/v1/data-store
     * <p>
     * Send the revision that was read in {@code If-Match} or {@code expectedRevision} to reject the update when
     * the entry changed in between. Without a revision, the update replaces the entry unconditionally.
     * </p>
     *
     * @param ifMatch optional entity tag of the expected revision, for example {@code "3"}
     * @param expectedRevision optional expected revision
     * @param dataStore the data store to update (must include ID)
     * @return HTTP 200 with the new revision in the {@code ETag} header
     */
    @PUT
    public RestResponse<WanakuResponse<Void>> update(
            @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
            @QueryParam("expectedRevision") Long expectedRevision,
            DataStore dataStore) {
        LOG.debugf("REST: Updating data store: %s", dataStore);
        DataStore stored = dataStoresBean.update(dataStore, expectedRevision(ifMatch, expectedRevision));
        return withEntityTag(new WanakuResponse<>(), record(stored));
    }

    /**
     * List all data stores, optionally filtered by label expression.
     * GET /api/v1/data-store
     * {@code GET /api/v1/data-store?labelFilter={expression}}
     *
     * @param labelFilter optional label expression to filter data stores
     * @return response with list of data stores
     */
    @GET
    public WanakuResponse<List<DataStoreRecord>> listOrGetByName(
            @QueryParam("labelFilter") String labelFilter, @QueryParam("name") String name) {
        if (name != null && !name.isEmpty()) {
            LOG.debugf("REST: Getting data stores by name: %s", name);
            List<DataStore> dataStores = dataStoresBean.findByName(name);
            if (dataStores == null || dataStores.isEmpty()) {
                throw new DataStoreResourceNotFoundException("Data store not found with name: %s".formatted(name));
            }
            return new WanakuResponse<>(records(dataStores));
        }

        if (labelFilter != null && !labelFilter.isBlank()) {
            LOG.debugf("REST: Listing data stores with label filter: %s", labelFilter);
        } else {
            LOG.debug("REST: Listing all data stores");
        }
        List<DataStore> dataStores = dataStoresBean.list(labelFilter);
        return new WanakuResponse<>(records(dataStores));
    }

    /**
     * Get a data store by ID.
     * GET /api/v1/data-store/{id}
     *
     * @param id the ID of the data store
     * @return response with the requested data store and its revision in the {@code ETag} header
     */
    @Path("/{id}")
    @GET
    public RestResponse<WanakuResponse<DataStoreRecord>> getById(@PathParam("id") String id) {
        LOG.debugf("REST: Getting data store by ID: %s", id);
        DataStore dataStore = dataStoresBean.findById(id);
        if (dataStore == null) {
            throw new DataStoreResourceNotFoundException("Data store not found with ID: %s".formatted(id));
        }
        DataStoreRecord result = record(dataStore);
        return withEntityTag(new WanakuResponse<>(result), result);
    }

    /**
     * Remove a data store by ID.
     * DELETE /api/v1/data-store/{id}
     *
     * @param id the ID of the data store to remove
     * @param ifMatch optional entity tag of the expected revision
     * @param expectedRevision optional expected revision
     * @return HTTP 200 if removed, 404 if not found, 409 if the revision does not match
     */
    @Path("/{id}")
    @DELETE
    public WanakuResponse<Void> removeById(
            @PathParam("id") String id,
            @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
            @QueryParam("expectedRevision") Long expectedRevision) {
        LOG.debugf("REST: Removing data store by ID: %s", id);
        int deleteCount = dataStoresBean.removeById(id, expectedRevision(ifMatch, expectedRevision));
        if (deleteCount > 0) {
            return new WanakuResponse<>();
        } else {
            throw new DataStoreResourceNotFoundException(id);
        }
    }

    /**
     * Remove data stores by name.
     * DELETE /api/v1/data-store?name={name}
     *
     * @param name the name of the data store(s) to remove
     * @return HTTP 200 if removed, 404 if not found
     */
    @DELETE
    public WanakuResponse<Void> removeByName(@QueryParam("name") String name) {
        if (StringHelper.isEmpty(name)) {
            throw new WanakuException("The 'name' query parameter must be provided");
        }

        LOG.debugf("REST: Removing data stores by name: %s", name);
        int deleteCount = dataStoresBean.remove(name);
        if (deleteCount > 0) {
            return new WanakuResponse<>();
        }
        throw new DataStoreResourceNotFoundException(name);
    }

    /**
     * Remove data stores matching a label expression.
     * DELETE /api/v1/data-store/labels?labelExpression={expression}
     *
     * @param labelExpression the label expression to match data stores for removal
     * @return response with count of removed data stores
     */
    @Path("/labels")
    @DELETE
    public WanakuResponse<Integer> removeIf(@QueryParam("labelExpression") String labelExpression) {
        LOG.debugf("REST: Removing data stores by label expression: %s", labelExpression);
        int removed = dataStoresBean.removeIf(labelExpression);
        return new WanakuResponse<>(removed);
    }

    /**
     * Resolves the expected revision from the {@code If-Match} header and the {@code expectedRevision} parameter.
     */
    static Long expectedRevision(String ifMatch, Long expectedRevision) {
        Long fromHeader = DataStoreRecord.parseEntityTag(ifMatch);
        if (fromHeader != null && expectedRevision != null && !fromHeader.equals(expectedRevision)) {
            throw new IllegalArgumentException("If-Match and expectedRevision specify different revisions");
        }
        return fromHeader != null ? fromHeader : expectedRevision;
    }

    private static <T> RestResponse<T> withEntityTag(T entity, DataStoreRecord stored) {
        return RestResponse.ResponseBuilder.ok(entity)
                .header(HttpHeaders.ETAG, DataStoreRecord.entityTag(stored.getRevision()))
                .build();
    }

    private static DataStoreRecord record(DataStore dataStore) {
        return dataStore instanceof DataStoreRecord stored ? stored : DataStoreRecord.of(dataStore);
    }

    private static List<DataStoreRecord> records(List<DataStore> dataStores) {
        return dataStores.stream().map(DataStoresResource::record).toList();
    }
}
