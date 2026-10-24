package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.GetTableResult;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides backwards compatibility for Hive 1.x.x servers.
 * Earlier Hive 2.x clients called get_table_req, which Hive 1.x servers
 * do not implement. This class provides a fallback to get_table.
 * Hive 2.3.10 already calls get_table directly; the fallback is retained
 * for TApplicationException handling.
 * <p>
 * The motivation for this approach is taken from here:
 * https://github.com/HotelsDotCom/waggle-dance/pull/133/files
 */
@SuppressWarnings("deprecation")
public class HiveMetaStoreClientCompatibility1xx extends HiveMetaStoreClient implements IMetaStoreClient {

    private final Logger LOG = LoggerFactory.getLogger(this.getClass());

    public HiveMetaStoreClientCompatibility1xx(HiveConf conf) throws MetaException {
        super(conf);
    }

    public HiveMetaStoreClientCompatibility1xx(HiveConf conf, HiveMetaHookLoader hookLoader) throws MetaException {
        super(conf, hookLoader);
    }

    public HiveMetaStoreClientCompatibility1xx(HiveConf conf, HiveMetaHookLoader hookLoader, Boolean allowEmbedded) throws MetaException {
        super(conf, hookLoader, allowEmbedded);
    }

    /**
     * Returns the table given a dbname and table name. This will fallback
     * in Hive 1.x.x servers when a get_table_req API call is made.
     *
     * @param dbname The database the table is located in.
     * @param name   Name of the table to fetch.
     * @return An object representing the table.
     * @throws TException A thrift communication error occurred
     */
    @Override
    public Table getTable(String dbname, String name) throws TException {
        try {
            return super.getTable(dbname, name);
        } catch (TException e) {
            try {
                LOG.debug("Couldn't invoke method getTable");
                if (e.getClass().isAssignableFrom(TApplicationException.class)) {
                    LOG.debug("Attempting to fallback");
                    Table table = client.get_table(dbname, name);
                    return new GetTableResult(table).getTable();
                }
            } catch (MetaException | NoSuchObjectException ex) {
                LOG.debug("Original exception not re-thrown", e);
                throw ex;
            } catch (TTransportException transportException) {
                /*
                Propagate a TTransportException to allow RetryingMetaStoreClient (which proxies this class) to retry connecting to the metastore.
                The number of retries can be set in the hive-site.xml using hive.metastore.failure.retries.
                 */
                throw transportException;
            } catch (Throwable t) {
                LOG.warn("Unable to run compatibility for metastore client method get_table_req. Will rethrow original exception: ", t);
            }
            throw e;
        }
    }
}
