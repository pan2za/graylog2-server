/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.rest.resources.system.indexer;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.graylog2.audit.AuditEventTypes;
import org.graylog2.audit.jersey.AuditEvent;
import org.graylog2.indexer.IndexSet;
import org.graylog2.indexer.IndexSetRegistry;
import org.graylog2.indexer.indexset.IndexSetConfig;
import org.graylog2.indexer.indexset.IndexSetService;
import org.graylog2.indexer.indices.jobs.IndexSetCleanupJob;
import org.graylog2.rest.resources.system.indexer.responses.IndexSetResponse;
import org.graylog2.rest.resources.system.indexer.responses.IndexSetSummary;
import org.graylog2.shared.rest.resources.RestResource;
import org.graylog2.shared.security.RestPermissions;
import org.graylog2.system.jobs.SystemJobConcurrencyException;
import org.graylog2.system.jobs.SystemJobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@RequiresAuthentication
@Api(value = "System/IndexSets", description = "Index sets")
@Path("/system/indices/index_sets")
@Produces(MediaType.APPLICATION_JSON)
public class IndexSetsResource extends RestResource {
    private static final Logger LOG = LoggerFactory.getLogger(IndexSetsResource.class);

    private final IndexSetService indexSetService;
    private final IndexSetRegistry indexSetRegistry;
    private final IndexSetCleanupJob.Factory indexSetCleanupJobFactory;
    private final SystemJobManager systemJobManager;

    @Inject
    public IndexSetsResource(final IndexSetService indexSetService,
                             final IndexSetRegistry indexSetRegistry,
                             final IndexSetCleanupJob.Factory indexSetCleanupJobFactory,
                             final SystemJobManager systemJobManager) {
        this.indexSetService = requireNonNull(indexSetService);
        this.indexSetRegistry = indexSetRegistry;
        this.indexSetCleanupJobFactory = requireNonNull(indexSetCleanupJobFactory);
        this.systemJobManager = systemJobManager;
    }

    @GET
    @Timed
    @ApiOperation(value = "Get a list of all index sets")
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "Unauthorized"),
    })
    public IndexSetResponse list(@ApiParam(name = "skip", value = "The number of elements to skip (offset).", required = true)
                                 @QueryParam("skip") @DefaultValue("0") int skip,
                                 @ApiParam(name = "limit", value = "The maximum number of elements to return.", required = true)
                                 @QueryParam("limit") @DefaultValue("0") int limit) {
        List<IndexSetSummary> indexSets;
        int count;

        if (limit > 0) {
            // First collect all index set ids the user is allowed to see.
            final Set<String> allowedIds = indexSetService.findAll().stream()
                    .filter(indexSet -> isPermitted(RestPermissions.INDEXSETS_READ, indexSet.id()))
                    .map(IndexSetConfig::id)
                    .collect(Collectors.toSet());

            indexSets = indexSetService.findPaginated(allowedIds, limit, skip).stream()
                    .map(IndexSetSummary::fromIndexSetConfig)
                    .collect(Collectors.toList());
            count = allowedIds.size();
        } else {
            indexSets = indexSetService.findAll().stream()
                    .filter(indexSetConfig -> isPermitted(RestPermissions.INDEXSETS_READ, indexSetConfig.id()))
                    .map(IndexSetSummary::fromIndexSetConfig)
                    .collect(Collectors.toList());
            count = indexSets.size();
        }

        return IndexSetResponse.create(count, indexSets);
    }

    @GET
    @Path("{id}")
    @Timed
    @ApiOperation(value = "Get index set")
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "Unauthorized"),
            @ApiResponse(code = 404, message = "Index set not found"),
    })
    public IndexSetSummary get(@ApiParam(name = "id", required = true)
                               @PathParam("id") String id) {
        checkPermission(RestPermissions.INDEXSETS_READ, id);
        return indexSetService.get(id)
                .map(IndexSetSummary::fromIndexSetConfig)
                .orElseThrow(() -> new NotFoundException("Couldn't load index set with ID <" + id + ">"));
    }

    @POST
    @Timed
    @ApiOperation(value = "Create index set")
    @RequiresPermissions(RestPermissions.INDEXSETS_CREATE)
    @Consumes(MediaType.APPLICATION_JSON)
    @AuditEvent(type = AuditEventTypes.INDEX_SET_CREATE)
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "Unauthorized"),
    })
    public IndexSetSummary save(@ApiParam(name = "Index set configuration", required = true)
                                @Valid @NotNull IndexSetSummary indexSet) {
        final IndexSetConfig savedObject = indexSetService.save(indexSet.toIndexSetConfig());
        return IndexSetSummary.fromIndexSetConfig(savedObject);
    }

    @PUT
    @Path("{id}")
    @Timed
    @ApiOperation(value = "Update index set")
    @AuditEvent(type = AuditEventTypes.INDEX_SET_UPDATE)
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "Unauthorized"),
            @ApiResponse(code = 409, message = "Mismatch of IDs in URI path and payload"),
    })
    public IndexSetSummary update(@ApiParam(name = "id", required = true)
                                  @PathParam("id") String id,
                                  @ApiParam(name = "Index set configuration", required = true)
                                  @Valid @NotNull IndexSetSummary indexSet) {
        checkPermission(RestPermissions.INDEXSETS_EDIT, id);
        if (indexSet.id() != null && !id.equals(indexSet.id())) {
            throw new ClientErrorException("Mismatch of IDs in URI path and payload", Response.Status.CONFLICT);
        }

        final IndexSetConfig savedObject = indexSetService.save(indexSet.toIndexSetConfig());

        return IndexSetSummary.fromIndexSetConfig(savedObject);
    }

    @DELETE
    @Path("{id}")
    @Timed
    @ApiOperation(value = "Delete index set")
    @AuditEvent(type = AuditEventTypes.INDEX_SET_DELETE)
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "Unauthorized"),
            @ApiResponse(code = 404, message = "Index set not found"),
    })
    public void delete(@ApiParam(name = "id", required = true)
                       @PathParam("id") String id,
                       @ApiParam(name = "delete_indices")
                       @QueryParam("delete_indices") @DefaultValue("true") boolean deleteIndices) {
        checkPermission(RestPermissions.INDEXSETS_DELETE, id);

        final IndexSet indexSet = getIndexSet(indexSetRegistry, id);

        if (indexSet.getConfig().isDefault()) {
            throw new BadRequestException("Default index set <" + indexSet.getConfig().id() + "> cannot be deleted!");
        }

        if (indexSetService.delete(id) == 0) {
            throw new NotFoundException("Couldn't delete index set with ID <" + id + ">");
        } else {
            if (deleteIndices) {
                try {
                    systemJobManager.submit(indexSetCleanupJobFactory.create(indexSet));
                } catch (SystemJobConcurrencyException e) {
                    LOG.error("Error running system job", e);
                }
            }
        }
    }
}