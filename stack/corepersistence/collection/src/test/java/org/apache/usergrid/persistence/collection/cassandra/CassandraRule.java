package org.apache.usergrid.persistence.collection.cassandra;


import java.io.File;
import java.io.IOException;

import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.FileUtils;

import com.google.common.io.Files;
import com.netflix.astyanax.test.EmbeddedCassandra;


/**
 * @TODO - I wanted this in the test module but unfortunately that will create a circular dep
 *         due to the inclusion of the MigrationManager
 */
public class CassandraRule extends ExternalResource {
    private static final Logger LOG = LoggerFactory.getLogger( CassandraRule.class );

    public static final int THRIFT_PORT = AvailablePortFinder.getNextAvailable();
    public static final int GOSSIP_PORT = AvailablePortFinder.getNextAvailable();

    private static final Object mutex = new Object();

    private static EmbeddedCassandra cass;

    private static boolean started = false;


    @Override
    protected void before() throws Throwable {
        if ( started ) {
            return;
        }

        synchronized ( mutex ) {

            //we're late to the party, bail
            if ( started ) {
                return;
            }


            File dataDir = Files.createTempDir();
            dataDir.deleteOnExit();

            //cleanup before we run, shouldn't be necessary, but had the directory exist during JVM kill
            if( dataDir.exists() ) {
                FileUtils.deleteRecursive( dataDir );
            }

            try {
                LOG.info( "Starting cassandra" );

                cass = new EmbeddedCassandra( dataDir, "Usergrid", THRIFT_PORT, GOSSIP_PORT );
                cass.start();

                LOG.info( "Cassandra started" );

                started = true;
            }
            catch ( IOException e ) {
                throw new RuntimeException( "Unable to start cassandra", e );
            }
        }
    }


    @Override
    protected void after() {

        //TODO TN.  this should only really happen when we shut down
//        cass.stop();
    }
}