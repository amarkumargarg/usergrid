/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.usergrid.corepersistence.pipeline.read.elasticsearch;


import org.apache.usergrid.persistence.core.metrics.MetricsFactory;
import org.apache.usergrid.persistence.index.EntityIndexFactory;
import org.apache.usergrid.persistence.index.SearchEdge;
import org.apache.usergrid.persistence.index.SearchTypes;
import org.apache.usergrid.persistence.model.entity.Id;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import static org.apache.usergrid.corepersistence.util.CpNamingUtils.createConnectionSearchEdge;


public class ElasticSearchConnectionFilter extends AbstractElasticSearchFilter {


    private final String connectionName;
    private final Optional<String> connectedEntityType;


    /**
     * Create a new instance of our command
     */
    @Inject
    public ElasticSearchConnectionFilter( final EntityIndexFactory entityIndexFactory,
                                          final MetricsFactory metricsFactory, @Assisted( "query" ) final String query,
                                          @Assisted( "connectionName" ) final String connectionName,
                                          @Assisted( "connectedEntityType" )
                                          final Optional<String> connectedEntityType ) {
        super( entityIndexFactory, metricsFactory, query );

        this.connectionName = connectionName;
        this.connectedEntityType = connectedEntityType;
    }


    @Override
    protected SearchTypes getSearchTypes() {
        final SearchTypes searchTypes = SearchTypes.fromNullableTypes( connectedEntityType.orNull() );

        return searchTypes;
    }


    @Override
    protected SearchEdge getSearchEdge( final Id id ) {
        final SearchEdge searchEdge = createConnectionSearchEdge( id, connectionName );

        return searchEdge;
    }
}
