/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on May 19, 2007
 */

package com.bigdata.rdf.store;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import com.bigdata.btree.IIndex;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.journal.ITx;
import com.bigdata.search.FullTextIndex;
import com.bigdata.service.DataService;
import com.bigdata.service.EmbeddedFederation;
import com.bigdata.service.GlobalRowStoreSchema;
import com.bigdata.service.IBigdataClient;
import com.bigdata.service.IBigdataFederation;
import com.bigdata.service.LocalDataServiceFederation;
import com.bigdata.service.jini.JiniFederation;
import com.bigdata.sparse.SparseRowStore;

/**
 * Implementation of an {@link ITripleStore} as a client of an
 * {@link IBigdataFederation}.
 * 
 * <h2>Deployment choices</h2>
 * 
 * You can deploy the {@link ScaleOutTripleStore} using any
 * {@link IBigdataClient}. The {@link LocalDataServiceFederation} preserves
 * full concurrency control and uses monolithic indices. An
 * {@link EmbeddedFederation} can be used if you want key-range partitioned
 * indices but plan to run on a single machine and do not want to incur the
 * overhead for RMI - all services will run in the same JVM. Finally, a
 * {@link JiniFederation} can be used if you want to use a scale-out deployment.
 * In this case indices will be key-range partitioned and will be automatically
 * re-distributed over the available resources.
 * 
 * <h2>Architecture</h2>
 *  
 * The client uses unisolated writes against the lexicon (terms and ids indices)
 * and the statement indices. The index writes are automatically broken down
 * into one split per index partition. While each unisolated write on an index
 * partition is ACID, the indices are fully consistent iff the total operation
 * is successfull. For the lexicon, this means that the write on the terms and
 * the ids index must both succeed. For the statement indices, this means that
 * the write on each access path must succeed. If a client fails while adding
 * terms, then it is possible for the ids index to be incomplete with respect to
 * the terms index (i.e., terms are mapped into the lexicon and term identifiers
 * are assigned but the reverse lookup by term identifier will not discover the
 * term). Likewise, if a client fails while adding statements, then it is
 * possible for some of the access paths to be incomplete with respect to the
 * other access paths (i.e., some statements are not present in some access
 * paths).
 * <p>
 * Two additional mechanisms are used in order to guarentee reads from only
 * fully consistent data. First, clients providing query answering should read
 * from a database state that is known to be consistent (by using a read-only
 * transaction whose start time is the globally agreed upon commit time for that
 * database state). Second, if a client operation fails then it must be retried.
 * Such fail-safe retry semantics are available when data load operations are
 * executed as part of a map-reduce job.
 * <p>
 * 
 * @todo provide a mechanism to make document loading robust to client failure.
 *       When loads are unisolated, a client failure can result in the
 *       statements being loaded into only a subset of the statement indices.
 *       robust load would require a means for undo or redo of failed loads. a
 *       loaded based on map/reduce would naturally provide a robust mechanism
 *       using a redo model.
 * 
 * FIXME Tune up inference for remote data services.
 * 
 * @todo provide batching and synchronization for database at once and TM update
 *       scenarios with a distributed {@link ITripleStore}.
 * 
 * @todo Write a distributed join for inference and high-level query.
 * 
 * @todo tune up SPARQL query (modified LUBM).
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ScaleOutTripleStore extends AbstractTripleStore {

    private final IBigdataFederation fed;
    
    private final String name;
    
    private final long timestamp;

    /**
     * The {@link IBigdataFederation} that is being used.
     */
    public IBigdataFederation getFederation() {
        
        return fed;
        
    }

    /**
     * The name of the connected {@link ITripleStore} as specified to the ctor.
     */
    public String getName() {
        
        return name;
        
    }
    
    /**
     * The timestamp of the view of the connected {@link ITripleStore} as
     * specified to the ctor.
     */
    public long getTimestamp() {
        
        return timestamp;
        
    }

    protected void assertWritable() {
        
        if(isReadOnly()) {
            
            throw new IllegalStateException("READ_ONLY");
            
        }
        
    }
    
    /**
     * Connect to a named {@link ITripleStore}.
     * <p>
     * Note: If the named {@link ITripleStore} does not exist AND the
     * <i>timestamp</i> is {@link ITx#UNISOLATED} then it will be created.
     * 
     * @param client
     *            The client. Configuration information is obtained from the
     *            client. See {@link Options} for configuration options.
     * 
     * @param name
     *            The name of the {@link ITripleStore}. Note that this also
     *            serves as the namespace for the indices used by that
     *            {@link ITripleStore}.
     * 
     * @param timestamp
     *            The timestamp associated with the view of the
     *            {@link ITripleStore}.
     * 
     * @throws IllegalStateException
     *             if the client is not connected.
     */
    public ScaleOutTripleStore(IBigdataClient client, String name, long timestamp) {

        super( client.getProperties() );

        if (name == null)
            throw new IllegalArgumentException();
        
        this.fed = client.getFederation();

        this.name = name;
        
        this.timestamp = timestamp;
        
        final SparseRowStore rowStore = fed.getGlobalRowStore();
        
        Map<String,Object> row = rowStore.read(fed.getKeyBuilder(), GlobalRowStoreSchema.INSTANCE, name);
        
        if( row == null ) {

            if (timestamp == ITx.UNISOLATED) {

                /*
                 * @todo race conditions in index registration could be handled
                 * by having a property that indicated whether or not the
                 * indices were in the process of being registered. The value of
                 * the property could be a UUID identifying the client. The
                 * property would be cleared once the indices were registered.
                 * Other clients observing the property would wait until it had
                 * been cleared. If not cleared within a timeout (say 10
                 * seconds) then the waiting client should assume that the
                 * client attempting to register the indices had died. In that
                 * case the client should itself assert the property and then
                 * attempt to register the indices itself - dropping existing
                 * indices is Ok IFF they are empty, otherwise we need a thrown
                 * exception and a flag to allow override.
                 * 
                 * To avoid a race condition when the client attemps to take a
                 * compensating action we need a means to update a property iff
                 * some pre-condition is satisified.
                 * 
                 * A similar approach could be used when taking a triple store
                 * offline and dropping its indices.
                 */
                
                row = new HashMap<String,Object>();
                
                row.put(GlobalRowStoreSchema.NAME, name);
                
                // Create configuration entry.
                row = rowStore.write(fed.getKeyBuilder(), GlobalRowStoreSchema.INSTANCE, row);

                // Register the necessary indices.
                registerIndices();
                
            } else {
                
                throw new RuntimeException("Not registered: "+name);
                
            }

        } else {

            setIndexObjects();
            
        }
        
    }
    
    /**
     * Register the indices.
     * 
     * @todo you should not be able to turn off the lexicon for the scale-out
     *       triple store (or for the local triple store). That option only
     *       makes sense for the {@link TempTripleStore}.
     */
    private void registerIndices() {

        log.info("");
        
        assertWritable();
        
        final IndexMetadata id2TermMetadata = getId2TermIndexMetadata(name+name_id2Term);
        
        final IndexMetadata term2IdMetadata = getTerm2IdIndexMetadata(name+name_term2Id);
        
        final IndexMetadata justMetadata = getJustIndexMetadata(name+name_just);
            
        // all known data service UUIDs.
        final UUID[] uuids = fed.getDataServiceUUIDs(0);
    
        if (true && uuids.length == 2 && lexicon && !oneAccessPath) {

            /*
             * Special case for (2) data services attempts to balance the write
             * volume and concurrent write load for the indices.
             * 
             * dataService0: terms, spo
             * 
             * dataService1: ids, pos, osp, just (if used)
             */

            log.warn("Special case allocation for two data services");
            
            fed.registerIndex(term2IdMetadata,
                    new byte[][] { new byte[] {} }, new UUID[] { uuids[0] });
            
            fed.registerIndex(id2TermMetadata,
                    new byte[][] { new byte[] {} }, new UUID[] { uuids[1] });
            
            /*
             * @todo could pre-partition based on the expected #of statements
             * for the store. If we want on the order of 20M statements per
             * partition and we expect at least 2B statements then we can
             * compute the #of required partitions. Since this is static
             * partitioning it will not be exact. This means that you can have
             * more statements in some partitions than in others - and this will
             * vary across the different access paths. It also means that the
             * last partition will absorb all statements beyond the expected
             * maximum.
             * 
             * The separator keys would be formed from the term identifiers that
             * would be assigned as [id:NULL:NULL]. We can use the same
             * separator keys for each of the statement indices.
             * 
             * Note: The term identifiers will be strictly incremental up to ~30
             * bits per index partition for the term:ids index (the index that
             * assigns the term identifiers). If there are multiple partitions
             * of the terms:ids index then the index partition identifier will
             * be non-zero after the first terms:ids index partition and the
             * logic to compute the ids for forming the statement index
             * separator keys would have to be changed.
             */
            
            fed.registerIndex(getStatementIndexMetadata(name+name_spo),
                    new byte[][] { new byte[] {} }, new UUID[] { uuids[0] });

            fed.registerIndex(getStatementIndexMetadata(name+name_pos),
                    new byte[][] { new byte[] {} }, new UUID[] { uuids[1] });

            fed.registerIndex(getStatementIndexMetadata(name+name_osp),
                    new byte[][] { new byte[] {} }, new UUID[] { uuids[1] });
            
            if(justify) {

                fed.registerIndex(justMetadata, new byte[][] { new byte[] {} },
                        new UUID[] { uuids[1] });
                
            }

        } else {

            /*
             * Allocation of index partitions to data services is governed by
             * the metadata service.
             */

            if (lexicon) {

                fed.registerIndex(term2IdMetadata);

                fed.registerIndex(id2TermMetadata);

            }

            fed.registerIndex(getStatementIndexMetadata(name+name_spo));

            if(!oneAccessPath) {

                fed.registerIndex(getStatementIndexMetadata(name+name_pos));

                fed.registerIndex(getStatementIndexMetadata(name+name_osp));

            }

            if (justify) {

                fed.registerIndex(justMetadata);

            }

        }

        setIndexObjects();
        
    }
    
    /**
     * (Re-)sets the index references (ids, terms, spo, etc).
     */
    private void setIndexObjects() {
        
        /*
         * Note: The term:id and id:term indices ALWAYS use unisolated operation
         * to ensure consistency without write-write conflicts.
         */

        term2id      = fed.getIndex(name+name_term2Id, ITx.UNISOLATED);
        id2term      = fed.getIndex(name+name_id2Term, ITx.UNISOLATED);
        
        /*
         * Note: if full transactions are to be used then the statement indices
         * and the justification indices should be assigned the transaction
         * identifier.
         */

        if(oneAccessPath) {
            
            spo      = fed.getIndex(name+name_spo, timestamp);
            pos      = null;
            osp      = null;
            
        } else {
            
            spo      = fed.getIndex(name+name_spo, timestamp);
            pos      = fed.getIndex(name+name_pos, timestamp);
            osp      = fed.getIndex(name+name_osp, timestamp);
            
        }

        if(justify) {

            just     = fed.getIndex(name+name_just, timestamp);
            
        } else {
            
            just = null;
            
        }

    }
    
    /**
     * Note: this is not an atomic drop/add and concurrent clients will NOT have
     * a coherent view of the database during a {@link #clear()}.
     * 
     * @todo It may not be possible to achieve atomic semantics for this
     *       {@link #clear()}. You are better taking the scale-out triple store
     *       off line entirely, e.g., by the atomic delete of the object that
     *       describes it, waiting until noone is running against the scale-out
     *       triple store, and then having a client that still holds that object
     *       drop all of the indices.
     */
    synchronized public void clear() {

        assertWritable();

        if (lexicon) {
         
            fed.dropIndex(name+name_id2Term); id2term = null;
            
            fed.dropIndex(name+name_term2Id); term2id = null;
        
            if(textIndex) {
                
                getSearchEngine().clear();
                
            }
            
        }
        
        if(oneAccessPath) {
            
            fed.dropIndex(name+name_spo); spo = null;
            
        } else {
            
            fed.dropIndex(name+name_spo); spo = null;
            
            fed.dropIndex(name+name_pos); pos = null;
            
            fed.dropIndex(name+name_osp); osp = null;
            
        }
    
        if(justify) {

            fed.dropIndex(name+name_just); just = null;
            
        }
        
        registerIndices();
        
    }
    
    /**
     * The terms index.
     */
    private IIndex term2id;

    /**
     * The ids index.
     */
    private IIndex id2term;

    /**
     * The statement indices for a triple store.
     */
    private IIndex spo, pos, osp;

    private IIndex just;
    
    final public IIndex getTerm2IdIndex() {

        if(term2id==null) throw new IllegalStateException();
        
        return term2id;

    }

    final public IIndex getId2TermIndex() {

        if(id2term==null) throw new IllegalStateException();

        return id2term;

    }

    final public IIndex getSPOIndex() {

        if(spo==null) throw new IllegalStateException();

        return spo;

    }

    final public IIndex getPOSIndex() {

        if(pos==null) throw new IllegalStateException();

        return pos;

    }

    final public IIndex getOSPIndex() {

        if(osp==null) throw new IllegalStateException();

        return osp;

    }

    final public IIndex getJustificationIndex() {

        if(just==null) throw new IllegalStateException();

        return just;

    }

    /**
     * This uses the {@link ExecutorService} returned by {@link IBigdataFederation#getThreadPool()}
     */
    public ExecutorService getThreadPool() {
        
        return fed.getThreadPool();
        
    }
    
    /**
     * NOP since the client uses unisolated writes which auto-commit.
     */
    final public void commit() {
        
        if(INFO) log.info(usage());
        
    }
    
    /**
     * NOP since the client uses unisolated writes which auto-commit.
     */
    final public void abort() {
        
    }
    
    /**
     * Note: A distributed federation is assumed to be stable.
     */
    final public boolean isStable() {

        if(fed instanceof LocalDataServiceFederation) {
            
            return ((DataService) ((LocalDataServiceFederation) fed)
                    .getDataService()).getResourceManager().getLiveJournal()
                    .isStable();

        }

        if( fed instanceof EmbeddedFederation) {
            
            return ((EmbeddedFederation)fed).isTransient();
            
        }
        
        // Assume federation is stable.
        return true;
        
    }

    /**
     * <code>true</code> unless {{@link #getTimestamp()} is {@link ITx#UNISOLATED}.
     */
    final public boolean isReadOnly() {
        
        return timestamp != ITx.UNISOLATED;
        
    }
    
//    final public void close() {
//        
//        super.close();
//        
//    }

    /**
     * Drops the indices for the {@link ITripleStore}.
     */
    public void closeAndDelete() {
        
        clear();
        
        super.closeAndDelete();
        
    }

    /**
     * This store is safe for concurrent operations.
     */
    public boolean isConcurrent() {

        return true;
        
    }
    
    /**
     * A factory returning the singleton read-committed view of the database.
     */
    public ITripleStore asReadCommittedView() {

        synchronized(this) {
        
            ITripleStore view = readCommittedRef == null ? null
                    : readCommittedRef.get();
            
            if(view == null) {
                
                view = new ScaleOutTripleStore(fed.getClient(), name,
                        ITx.READ_COMMITTED);
                
                readCommittedRef = new SoftReference<ITripleStore>(view);
                
            }
            
            return view; 
        
        }
        
    }
    private SoftReference<ITripleStore> readCommittedRef;
    
    /**
     * A factory returning the singleton for the {@link FullTextIndex}.
     */
    public FullTextIndex getSearchEngine() {

        synchronized(this) {
        
            FullTextIndex view = searchEngineRef == null ? null
                    : searchEngineRef.get();
            
            if(view == null) {
                
                view = new FullTextIndex(fed.getClient(),name/*namespace*/);
                
                searchEngineRef = new SoftReference<FullTextIndex>(view);
                
            }
            
            return view; 
        
        }
        
    }
    private SoftReference<FullTextIndex> searchEngineRef;
    
}
