/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.river.rethinkdb;

import com.rethinkdb.Cursor;
import com.rethinkdb.RethinkDB;
import com.rethinkdb.RethinkDBConnection;
import com.rethinkdb.RethinkDBException;
import com.rethinkdb.ast.query.RqlQuery;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import java.util.*;

/**
 *
 */
public class RethinkDBRiver extends AbstractRiverComponent implements River {

    private static final RethinkDB r = RethinkDB.r;
    private final Client client;

    public final String hostname;
    public final int port;
    public final String authKey;
    private final ChangeRecords changeRecords;

    private volatile List<Thread> threads;
    private volatile boolean closed;

    @SuppressWarnings({"unchecked"})
    @Inject
    public RethinkDBRiver(
            RiverName riverName,
            RiverSettings settings,
            Client client) {
        super(riverName, settings);
        this.client = client;

        // Get ye settings from the document:
        // Expected settings document (angle brackets indicate it stands in for a real name)
        // "rethinkdb": {
        //   "host": <hostname (default: "localhost")>,
        //   "port": <port (default: 28015)>,
        //   "auth_key": <authKey (default: "")>,
        //   "databases": {
        //     <dbName>: {
        //       <tableName>: {
        //         "backfill": <whether to backfill (default: true)>,
        //         "index": <indexName (default: <dbName>)>,
        //         "type": <typeName (default: <tableName>)>
        //       },
        //       ... more tables in <dbName>
        //     }
        //     ... more databases
        //   }
        // }
        try {
            Map<String, Object> rdbSettings = jsonGet(
                    settings.settings(), "rethinkdb", new HashMap<>());
            hostname = jsonGet(rdbSettings, "host", "localhost");
            port = jsonGet(rdbSettings, "port", 28015);
            authKey = jsonGet(rdbSettings, "auth_key", "");
            Map<String, ? extends Map<String, ? extends Map<String, Object>>> tables = jsonGet(
                    rdbSettings, "databases", new HashMap<>());

            // Create thy changeRecords to hold the configuration
            changeRecords = new ChangeRecords(tables);
            logger.info("ChangeRecords: {}", changeRecords);
            threads = new ArrayList();
            closed = false;
        }catch(Exception e){
            logger.error("Initializing the RethinkDB River failed. " +
                    " Is your configuration in the right format?" + e.getMessage());
            throw e;
        }
    }

    @Override
    public void start() {
        // Start up all the listening threads
        logger.info("Starting up RethinkDB River for {}:{}", hostname, port);
        for(ChangeInfo changeinfo : changeRecords.getAll()) {
            Thread thread = EsExecutors
                    .daemonThreadFactory(settings.globalSettings(), "rethinkdb_river")
                    .newThread(new FeedWorker(changeinfo));
            thread.start();
            logger.info("Starting feed watcher for {}.{}", changeinfo.db, changeinfo.table);
            threads.add(thread);
        }
    }

    @Override
    public void close() {
        if(closed){
            return;
        }
        logger.info("Closing RethinkDB River");
        closed = true;
        for(Thread thread: threads){
            thread.interrupt();
        }

    }

    private class FeedWorker implements Runnable {

        private ChangeInfo changeinfo;
        private RethinkDBConnection connection;
        private Cursor<Map<String,Object>> cursor;
        private String primaryKey;

        public FeedWorker(ChangeInfo changeinfo){
            this.changeinfo = changeinfo;
        }

        private RethinkDBConnection connect(){
            RethinkDBConnection conn = r.connect(hostname, port, authKey);
            conn.use(changeinfo.db);
            return conn;
        }

        private void close(){
            if (cursor != null){
                cursor.close();
            }
            if (connection != null){
                connection.close();
            }
        }

        @Override
        public void run() {
            try {
                connection = connect();
                primaryKey = getPrimaryKey();
                while (!closed) {
                    try {
                        cursor = changeinfo.query.runForCursor(connection);
                        while (cursor.hasNext()) {
                            Map<String, Object> change = cursor.next();
                            logger.info("Inserting: {}", change); //TODO: deleteme
                            client.prepareIndex(
                                    changeinfo.targetIndex,
                                    changeinfo.targetType,
                                    (String) change.get(primaryKey))
                                    .setSource(change)
                                    .execute();
                        }
                    } catch (RethinkDBException e) {
                        logger.error(e.getMessage());
                        if(isRecoverableError(e)){
                            logger.info("I think this is recoverable. Hang on a second...");
                            reconnect();
                        } else {
                            logger.info("This probably isn't recoverable, bailing.");
                            throw e;
                        }
                    }
                }
            } catch (Exception e){
                logger.error("FeedWorker for {}.{} failed due to exception", e, changeinfo.db, changeinfo.table);
            } finally {
                logger.info("FeedWorker thread for {}.{} shutting down", changeinfo.db, changeinfo.table);
                close();
            }
        }

        private String getPrimaryKey() {
            Map<String, Object> tableInfo = (Map) r.db(changeinfo.db).table(changeinfo.table).info().run(connection);
            return (String) tableInfo.get("primary_key");
        }

        private boolean isRecoverableError(RethinkDBException exc){
            String msg = exc.getMessage();
            return msg.matches(".*?Master for shard \\[.*\\) not available.*") // happens immediately after the db starts up, temporary
                    || msg.matches(".*?Error receiving data.*") // happens when the database shuts down while we're waiting
                    || msg.matches(".*?Query interrupted.*")    // happens when a query is killed? maybe.
                    ;
        }

        private void reconnect() throws InterruptedException {
            Thread.sleep(250);
            logger.info("Attempting to reconnect to {}:{}", hostname, port);
            for(int i = 500;; i = Math.min(i*2, 30000)){
                try {
                    connect();
                }catch(RethinkDBException e){
                    logger.error("Reconnect failed, waiting {}ms before trying again", i);
                    Thread.sleep(i);
                    continue;
                }
                logger.info("Reconnection successful.");
                //Thread.sleep(500);
                break;
            }
        }
    }

    @SuppressWarnings({"unchecked"})
    public static <V> V jsonGet(Map<String,Object> map, String key, V defaultValue){
        return map.containsKey(key) ? (V) map.get(key) : defaultValue;
    }

    public class ChangeInfo {
        public final RqlQuery query;
        public final String table;
        public final String db;
        public final boolean backfill;
        public final String targetIndex;
        public final String targetType;

        public ChangeInfo(String db, String table, Map<String,Object> options){
            this.db = db;
            this.table = table;
            this.backfill = (boolean) options.getOrDefault("backfill", false);
            this.targetIndex = (String) options.getOrDefault("index", db);
            this.targetType = (String) options.getOrDefault("type", table);
            query = r.table(table).changes().field("new_val");
        }

        @Override
        public String toString(){
            return "ChangeRecord(" + db + "," + table + "," +
                    (backfill ? "backfill,": "no backfill,") +
                    (!targetIndex.equals(db) ? "index=" + targetIndex + ",": "") +
                    (!targetType.equals(table) ? "type=" + targetType : "");
        }
    }

    public class ChangeRecords implements Iterable {

        public int totalSize;
        private HashMap<String, HashMap<String, ChangeInfo>> hash;

        public ChangeRecords(Map<String, ? extends Map<String, ? extends Map<String, Object>>> dbs){
            totalSize = 0;
            hash = new HashMap<>();
            for(String dbName: dbs.keySet()){
                for(String tableName: dbs.get(dbName).keySet()){
                    if (!hash.containsKey(dbName)){
                        hash.put(dbName, new HashMap<>());
                    }
                    hash.get(dbName).put(tableName, new ChangeInfo(dbName, tableName, dbs.get(dbName).get(tableName)));
                    totalSize++;
                }
            }
        }

        public ChangeInfo get(String db, String table){
            return hash.get(db).get(table);
        }

        @Override
        public String toString(){
            return "ChangeRecords(" + hash.toString() + ")";
        }

        @Override
        public Iterator<ChangeInfo> iterator(){
            return getAll().iterator();
        }

        public List<ChangeInfo> getAll(){
            ArrayList<ChangeInfo> ret = new ArrayList<>(totalSize);
            for(HashMap tables: hash.values()){
                ret.addAll(tables.values());
            }
            return ret;
        }
    }
}
