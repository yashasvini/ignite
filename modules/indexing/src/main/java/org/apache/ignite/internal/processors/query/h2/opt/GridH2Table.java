/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.opt;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteInterruptedException;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.query.h2.database.H2RowFactory;
import org.apache.ignite.internal.processors.query.h2.database.H2TreeIndex;
import org.apache.ignite.internal.util.offheap.unsafe.GridUnsafeMemory;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.lang.IgniteBiTuple;
import org.h2.api.TableEngine;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.Session;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.IndexColumn;
import org.h2.table.TableBase;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentHashMap8;
import org.jsr166.LongAdder8;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2AbstractKeyValueRow.KEY_COL;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2AbstractKeyValueRow.VAL_COL;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2QueryType.MAP;

/**
 * H2 Table implementation.
 */
public class GridH2Table extends TableBase {
    /** */
    private final String spaceName;

    /** */
    private final GridH2RowDescriptor desc;

    /** */
    private final ArrayList<Index> idxs;

    /** */
    private final ReadWriteLock lock;

    /** */
    private boolean destroyed;

    /** */
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap8<Session,Boolean>());

    /** */
    private final AtomicReference<Object[]> actualSnapshot = new AtomicReference<>();

    /** */
    private IndexColumn affKeyCol;

    /** */
    private final LongAdder8 size = new LongAdder8();

    /** */
    private final boolean snapshotEnabled;

    /** */
    private final H2RowFactory rowFactory;

    /** */
    private volatile boolean rebuildFromHashInProgress;

    /**
     * Creates table.
     *
     * @param createTblData Table description.
     * @param desc Row descriptor.
     * @param idxsFactory Indexes factory.
     * @param spaceName Space name.
     */
    public GridH2Table(CreateTableData createTblData, @Nullable GridH2RowDescriptor desc, IndexesFactory idxsFactory,
        @Nullable String spaceName) {
        super(createTblData);

        assert idxsFactory != null;

        this.desc = desc;
        this.spaceName = spaceName;

        if (desc != null && desc.context() != null && !desc.context().customAffinityMapper()) {
            boolean affinityColExists = true;

            String affKey = desc.type().affinityKey();

            int affKeyColId = -1;

            if (affKey != null) {
                String colName = desc.context().config().isSqlEscapeAll() ? affKey : affKey.toUpperCase();

                if (doesColumnExist(colName))
                    affKeyColId = getColumn(colName).getColumnId();
                else
                    affinityColExists = false;
            }
            else
                affKeyColId = KEY_COL;

            if (affinityColExists) {
                affKeyCol = indexColumn(affKeyColId, SortOrder.ASCENDING);

                assert affKeyCol != null;
            }
        }

        // Indexes must be created in the end when everything is ready.
        rowFactory = idxsFactory.createRowFactory(this);
        idxs = idxsFactory.createIndexes(this);

        assert idxs != null;

        // Add scan index at 0 which is required by H2.
        if (idxs.size() >= 2
                && index(0).getIndexType().isHash())
            idxs.add(0, new ScanIndex(index(1), index(0)));
        else
            idxs.add(0, new ScanIndex(index(0), null));

        snapshotEnabled = desc == null || desc.snapshotableIndex();

        lock = new ReentrantReadWriteLock();
    }

    /**
     * @return {@code true} If this is a partitioned table.
     */
    public boolean isPartitioned() {
        return desc != null && desc.configuration().getCacheMode() == PARTITIONED;
    }

    /**
     * @return Affinity key column or {@code null} if not available.
     */
    @Nullable public IndexColumn getAffinityKeyColumn() {
        return affKeyCol;
    }

    /** {@inheritDoc} */
    @Override public long getDiskSpaceUsed() {
        return 0;
    }

    /**
     * @return Row descriptor.
     */
    public GridH2RowDescriptor rowDescriptor() {
        return desc;
    }

    /**
     * Should be called when entry is swapped.
     *
     * @param key Entry key.
     * @return {@code true} If row was found.
     * @throws IgniteCheckedException If failed.
     */
    public boolean onSwap(KeyCacheObject key, int partId) throws IgniteCheckedException {
        return onSwapUnswap(key, partId, null);
    }

    /**
     * Should be called when entry is unswapped.
     *
     * @param key Key.
     * @param val Value.
     * @return {@code true} If row was found.
     * @throws IgniteCheckedException If failed.
     */
    public boolean onUnswap(KeyCacheObject key, int partId, CacheObject val) throws IgniteCheckedException {
        assert val != null : "Key=" + key;

        return onSwapUnswap(key, partId, val);
    }

    /**
     * Swaps or unswaps row.
     *
     * @param key Key.
     * @param val Value for promote or {@code null} if we have to swap.
     * @return {@code true} if row was found and swapped/unswapped.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("LockAcquiredButNotSafelyReleased")
    private boolean onSwapUnswap(KeyCacheObject key, int partId, @Nullable CacheObject val) throws IgniteCheckedException {
        assert key != null;

        GridH2IndexBase pk = pk();

        assert desc != null;

        GridH2Row searchRow = desc.createRow(key, partId, null, null, 0);

        GridUnsafeMemory mem = desc.memory();

        lock(false);

        if (mem != null)
            desc.guard().begin();

        try {
            GridH2AbstractKeyValueRow row = (GridH2AbstractKeyValueRow)pk.findOne(searchRow);

            if (row == null)
                return false;

            if (val == null)
                row.onSwap();
            else
                row.onUnswap(val, false);

            return true;
        }
        finally {
            unlock(false);

            if (mem != null)
                desc.guard().end();
        }
    }

    /**
     * @return Space name.
     */
    @Nullable public String spaceName() {
        return spaceName;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"LockAcquiredButNotSafelyReleased", "SynchronizationOnLocalVariableOrMethodParameter", "unchecked"})
    @Override public boolean lock(@Nullable final Session ses, boolean exclusive, boolean force) {
        if (ses != null) {
            if (!sessions.add(ses))
                return false;

            ses.addLock(this);
        }

        if (snapshotInLock())
            snapshotIndexes(null);

        return false;
    }

    /**
     * @return {@code True} If we must snapshot and release index snapshots in {@link #lock(Session, boolean, boolean)}
     * and {@link #unlock(Session)} methods.
     */
    private boolean snapshotInLock() {
        if (!snapshotEnabled)
            return false;

        GridH2QueryContext qctx = GridH2QueryContext.get();

        // On MAP queries with distributed joins we lock tables before the queries.
        return qctx == null || qctx.type() != MAP || !qctx.hasIndexSnapshots();
    }

    /**
     * @param qctx Query context.
     */
    public void snapshotIndexes(GridH2QueryContext qctx) {
        if (!snapshotEnabled)
            return;

        Object[] snapshots;

        // Try to reuse existing snapshots outside of the lock.
        for (long waitTime = 200;; waitTime *= 2) { // Increase wait time to avoid starvation.
            snapshots = actualSnapshot.get();

            if (snapshots != null) { // Reuse existing snapshot without locking.
                snapshots = doSnapshotIndexes(snapshots, qctx);

                if (snapshots != null)
                    return; // Reused successfully.
            }

            if (tryLock(true, waitTime))
                break;
        }

        try {
            // Try again inside of the lock.
            snapshots = actualSnapshot.get();

            if (snapshots != null) // Try reusing.
                snapshots = doSnapshotIndexes(snapshots, qctx);

            if (snapshots == null) { // Reuse failed, produce new snapshots.
                snapshots = doSnapshotIndexes(null, qctx);

                assert snapshots != null;

                actualSnapshot.set(snapshots);
            }
        }
        finally {
            unlock(true);
        }
    }

    /**
     * @return Table identifier.
     */
    public String identifier() {
        return getSchema().getName() + '.' + getName();
    }

    /**
     * Acquire table lock.
     *
     * @param exclusive Exclusive flag.
     */
    private void lock(boolean exclusive) {
        Lock l = exclusive ? lock.writeLock() : lock.readLock();

        try {
            l.lockInterruptibly();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedException("Thread got interrupted while trying to acquire table lock.", e);
        }

        if (destroyed) {
            unlock(exclusive);

            throw new IllegalStateException("Table " + identifier() + " already destroyed.");
        }
    }

    /**
     * @param exclusive Exclusive lock.
     * @param waitMillis Milliseconds to wait for the lock.
     * @return Whether lock was acquired.
     */
    private boolean tryLock(boolean exclusive, long waitMillis) {
        Lock l = exclusive ? lock.writeLock() : lock.readLock();

        try {
            if (!l.tryLock(waitMillis, TimeUnit.MILLISECONDS))
                return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedException("Thread got interrupted while trying to acquire table lock.", e);
        }

        if (destroyed) {
            unlock(exclusive);

            throw new IllegalStateException("Table " + identifier() + " already destroyed.");
        }

        return true;
    }

    /**
     * Release table lock.
     *
     * @param exclusive Exclusive flag.
     */
    private void unlock(boolean exclusive) {
        Lock l = exclusive ? lock.writeLock() : lock.readLock();

        l.unlock();
    }

    /**
     * Must be called inside of write lock because when using multiple indexes we have to ensure that all of them have
     * the same contents at snapshot taking time.
     *
     * @param qctx Query context.
     * @return New indexes data snapshot.
     */
    @SuppressWarnings("unchecked")
    private Object[] doSnapshotIndexes(Object[] snapshots, GridH2QueryContext qctx) {
        assert snapshotEnabled;

        if (snapshots == null) // Nothing to reuse, create new snapshots.
            snapshots = new Object[idxs.size() - 2];

        // Take snapshots on all except first which is scan and second which is hash.
        for (int i = 2, len = idxs.size(); i < len; i++) {
            Object s = snapshots[i - 2];

            boolean reuseExisting = s != null;

            s = index(i).takeSnapshot(s, qctx);

            if (reuseExisting && s == null) { // Existing snapshot was invalidated before we were able to reserve it.
                // Release already taken snapshots.
                if (qctx != null)
                    qctx.clearSnapshots();

                for (int j = 2; j < i; j++)
                    index(j).releaseSnapshot();

                // Drop invalidated snapshot.
                actualSnapshot.compareAndSet(snapshots, null);

                return null;
            }

            snapshots[i - 2] = s;
        }

        return snapshots;
    }

    /** {@inheritDoc} */
    @Override public void close(Session ses) {
        // No-op.
    }

    /**
     * Destroy the table.
     */
    public void destroy() {
        lock(true);

        try {
            assert sessions.isEmpty() : sessions;

            destroyed = true;

            for (int i = 1, len = idxs.size(); i < len; i++)
                index(i).destroy();
        }
        finally {
            unlock(true);
        }
    }

    /** {@inheritDoc} */
    @Override public void unlock(@Nullable Session ses) {
        if (ses != null && !sessions.remove(ses))
            return;

        if (snapshotInLock())
            releaseSnapshots();
    }

    /**
     * Releases snapshots.
     */
    public void releaseSnapshots() {
        if (!snapshotEnabled)
            return;

        releaseSnapshots0(idxs);
    }

    /**
     * @param idxs Indexes.
     */
    private void releaseSnapshots0(ArrayList<Index> idxs) {
        // Release snapshots on all except first which is scan and second which is hash.
        for (int i = 2, len = idxs.size(); i < len; i++)
            ((GridH2IndexBase)idxs.get(i)).releaseSnapshot();
    }

    /**
     * Updates table for given key. If value is null then row with given key will be removed from table,
     * otherwise value and expiration time will be updated or new row will be added.
     *
     * @param key Key.
     * @param val Value.
     * @param expirationTime Expiration time.
     * @param rmv If {@code true} then remove, else update row.
     * @return {@code true} If operation succeeded.
     * @throws IgniteCheckedException If failed.
     */
    public boolean update(KeyCacheObject key,
        int partId,
        CacheObject val,
        GridCacheVersion ver,
        long expirationTime,
        boolean rmv,
        long link)
        throws IgniteCheckedException {
        assert desc != null;

        GridH2Row row = desc.createRow(key, partId, val, ver, expirationTime);

        row.link = link;

        if (!rmv)
            ((GridH2AbstractKeyValueRow)row).valuesCache(new Value[getColumns().length]);

        try {
            return doUpdate(row, rmv);
        }
        finally {
            if (!rmv)
                ((GridH2AbstractKeyValueRow)row).valuesCache(null);
        }
    }

    /**
     * @param key Key to read.
     * @return Read value.
     * @throws IgniteCheckedException If failed.
     */
    public IgniteBiTuple<CacheObject, GridCacheVersion> read(
        GridCacheContext cctx,
        KeyCacheObject key,
        int partId
    ) throws IgniteCheckedException {
        assert desc != null;

        GridH2Row row = desc.createRow(key, partId, null, null, 0);

        GridH2IndexBase primaryIdx = pk();

        GridH2Row res = primaryIdx.findOne(row);

        return res != null ? F.t(res.val, res.ver) : null;
    }

    /**
     * Gets index by index.
     *
     * @param idx Index in list.
     * @return Index.
     */
    private GridH2IndexBase index(int idx) {
        return (GridH2IndexBase)idxs.get(idx);
    }

    /**
     * Gets primary key.
     *
     * @return Primary key.
     */
    private GridH2IndexBase pk() {
        return (GridH2IndexBase)idxs.get(2);
    }

    /**
     * For testing only.
     *
     * @param row Row.
     * @param del If given row should be deleted from table.
     * @return {@code True} if operation succeeded.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("LockAcquiredButNotSafelyReleased")
    boolean doUpdate(final GridH2Row row, boolean del) throws IgniteCheckedException {
        // Here we assume that each key can't be updated concurrently and case when different indexes
        // getting updated from different threads with different rows with the same key is impossible.
        GridUnsafeMemory mem = desc == null ? null : desc.memory();

        lock(false);

        if (mem != null)
            desc.guard().begin();

        try {
            GridH2IndexBase pk = pk();

            if (!del) {
                assert rowFactory == null || row.link != 0 : row;

                GridH2Row old = pk.put(row); // Put to PK.

                if (old instanceof GridH2AbstractKeyValueRow) { // Unswap value on replace.
                    GridH2AbstractKeyValueRow kvOld = (GridH2AbstractKeyValueRow)old;

                    kvOld.onUnswap(kvOld.getValue(VAL_COL), true);
                }
                else if (old == null)
                    size.increment();

                int len = idxs.size();

                int i = 2;

                // Put row if absent to all indexes sequentially.
                // Start from 3 because 0 - Scan (don't need to update), 1 - PK hash (already updated), 2 - PK (already updated).
                while (++i < len) {
                    GridH2IndexBase idx = index(i);

                    assert !idx.getIndexType().isUnique() : "Unique indexes are not supported: " + idx;

                    GridH2Row old2 = idx.put(row);

                    if (old2 != null) { // Row was replaced in index.
                        if (!eq(pk, old2, old))
                            throw new IllegalStateException("Row conflict should never happen, unique indexes are " +
                                "not supported [idx=" + idx + ", old=" + old + ", old2=" + old2 + ']');
                    }
                    else if (old != null) // Row was not replaced, need to remove manually.
                        idx.removex(old);
                }
            }
            else {
                //  index(1) is PK, get full row from there (search row here contains only key but no other columns).
                GridH2Row old = pk.remove(row);

                if (row.getColumnCount() != 1 && old instanceof GridH2AbstractKeyValueRow) { // Unswap value.
                    Value v = row.getValue(VAL_COL);

                    if (v != null)
                        ((GridH2AbstractKeyValueRow)old).onUnswap(v.getObject(), true);
                }

                if (old != null) {
                    // Remove row from all indexes.
                    // Start from 3 because 0 - Scan (don't need to update), 1 - PK hash (already updated), 2 - PK (already updated).
                    for (int i = 3, len = idxs.size(); i < len; i++) {
                        Row res = index(i).remove(old);

                        assert eq(pk, res, old) : "\n" + old + "\n" + res + "\n" + i + " -> " + index(i).getName();
                    }

                    size.decrement();
                }
                else
                    return false;
            }

            // The snapshot is not actual after update.
            actualSnapshot.set(null);

            return true;
        }
        finally {
            unlock(false);

            if (mem != null)
                desc.guard().end();
        }
    }

    /**
     * Check row equality.
     *
     * @param pk Primary key index.
     * @param r1 First row.
     * @param r2 Second row.
     * @return {@code true} if rows are the same.
     */
    private static boolean eq(Index pk, SearchRow r1, SearchRow r2) {
        return r1 == r2 || (r1 != null && r2 != null && pk.compareRows(r1, r2) == 0);
    }

    /**
     * For testing only.
     *
     * @return Indexes.
     */
    ArrayList<GridH2IndexBase> indexes() {
        ArrayList<GridH2IndexBase> res = new ArrayList<>(idxs.size() - 2);

        for (int i = 2, len = idxs.size(); i < len; i++)
            res.add(index(i));

        return res;
    }

    /**
     *
     */
    public void markRebuildFromHashInProgress(boolean value) {
        rebuildFromHashInProgress = value;
    }

    /**
     *
     */
    public boolean rebuildFromHashInProgress() {
        return rebuildFromHashInProgress;
    }

    /** {@inheritDoc} */
    @Override public Index addIndex(Session ses, String s, int i, IndexColumn[] idxCols, IndexType idxType,
        boolean b, String s1) {
        throw DbException.getUnsupportedException("addIndex");
    }

    /** {@inheritDoc} */
    @Override public void removeRow(Session ses, Row row) {
        throw DbException.getUnsupportedException("removeRow");
    }

    /** {@inheritDoc} */
    @Override public void truncate(Session ses) {
        throw DbException.getUnsupportedException("truncate");
    }

    /** {@inheritDoc} */
    @Override public void addRow(Session ses, Row row) {
        throw DbException.getUnsupportedException("addRow");
    }

    /** {@inheritDoc} */
    @Override public void checkSupportAlter() {
        throw DbException.getUnsupportedException("alter");
    }

    /** {@inheritDoc} */
    @Override public String getTableType() {
        return EXTERNAL_TABLE_ENGINE;
    }

    /** {@inheritDoc} */
    @Override public Index getScanIndex(Session ses) {
        return getIndexes().get(0); // Scan must be always first index.
    }

    /** {@inheritDoc} */
    @Override public Index getUniqueIndex() {
        if (rebuildFromHashInProgress)
            return index(1);
        else
            return index(2);
    }

    /** {@inheritDoc} */
    @Override public ArrayList<Index> getIndexes() {
        if (!rebuildFromHashInProgress)
            return idxs;

        ArrayList<Index> idxs = new ArrayList<>(2);

        idxs.add(this.idxs.get(0));
        idxs.add(this.idxs.get(1));

        return idxs;
    }

    /**
     * @return All indexes, even marked for rebuild.
     */
    public ArrayList<Index> getAllIndexes() {
        return idxs;
    }

    /** {@inheritDoc} */
    @Override public boolean isLockedExclusively() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isLockedExclusivelyBy(Session ses) {
        return false;
    }

    /** {@inheritDoc} */
    @Override public long getMaxDataModificationId() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override public boolean isDeterministic() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean canGetRowCount() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean canDrop() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public long getRowCount(@Nullable Session ses) {
        return getUniqueIndex().getRowCount(ses);
    }

    /** {@inheritDoc} */
    @Override public long getRowCountApproximation() {
        return size.longValue();
    }

    /** {@inheritDoc} */
    @Override public void checkRename() {
        throw DbException.getUnsupportedException("rename");
    }

    /**
     * Creates index column for table.
     *
     * @param col Column index.
     * @param sorting Sorting order {@link SortOrder}
     * @return Created index column.
     */
    public IndexColumn indexColumn(int col, int sorting) {
        IndexColumn res = new IndexColumn();

        res.column = getColumn(col);
        res.columnName = res.column.getName();
        res.sortType = sorting;

        return res;
    }

    /**
     * @return Data store.
     */
    public H2RowFactory rowFactory() {
        return rowFactory;
    }

    /**
     * H2 Table engine.
     */
    @SuppressWarnings({"PublicInnerClass", "FieldAccessedSynchronizedAndUnsynchronized"})
    public static class Engine implements TableEngine {
        /** */
        private static GridH2RowDescriptor rowDesc;

        /** */
        private static IndexesFactory idxsFactory;

        /** */
        private static GridH2Table resTbl;

        /** */
        private static String spaceName;

        /** {@inheritDoc} */
        @Override public TableBase createTable(CreateTableData createTblData) {
            resTbl = new GridH2Table(createTblData, rowDesc, idxsFactory, spaceName);

            return resTbl;
        }

        /**
         * Creates table using given connection, DDL clause for given type descriptor and list of indexes.
         *
         * @param conn Connection.
         * @param sql DDL clause.
         * @param desc Row descriptor.
         * @param factory Indexes factory.
         * @param space Space name.
         * @throws SQLException If failed.
         * @return Created table.
         */
        public static synchronized GridH2Table createTable(Connection conn, String sql,
            @Nullable GridH2RowDescriptor desc, IndexesFactory factory, String space)
            throws SQLException {
            rowDesc = desc;
            idxsFactory = factory;
            spaceName = space;

            try {
                try (Statement s = conn.createStatement()) {
                    s.execute(sql + " engine \"" + Engine.class.getName() + "\"");
                }

                return resTbl;
            }
            finally {
                resTbl = null;
                idxsFactory = null;
                rowDesc = null;
            }
        }
    }

    /**
     * Type which can create indexes list for given table.
     */
    @SuppressWarnings({"PackageVisibleInnerClass", "PublicInnerClass"})
    public static interface IndexesFactory {
        /**
         * Create list of indexes. First must be primary key, after that all unique indexes and
         * only then non-unique indexes.
         * All indexes must be subtypes of {@link H2TreeIndex}.
         *
         * @param tbl Table to create indexes for.
         * @return List of indexes.
         */
        ArrayList<Index> createIndexes(GridH2Table tbl);

        /**
         * @param tbl Table.
         * @return Data store.
         */
        H2RowFactory createRowFactory(GridH2Table tbl);
    }

    /**
     * Wrapper type for primary key.
     */
    @SuppressWarnings("PackageVisibleInnerClass")
    class ScanIndex extends GridH2ScanIndex<GridH2IndexBase> {
        /** */
        static final String SCAN_INDEX_NAME_SUFFIX = "__SCAN_";

        /** */
        private final GridH2IndexBase hashIdx;

        /**
         * Constructor.
         */
        private ScanIndex(GridH2IndexBase treeIdx, GridH2IndexBase hashIdx) {
            super(treeIdx);

            this.hashIdx = hashIdx;
        }

        /**
         *
         */
        @Override protected GridH2IndexBase delegate() {
            if (hashIdx != null)
                return rebuildFromHashInProgress ? hashIdx : super.delegate();
            else {
                assert !rebuildFromHashInProgress;

                return super.delegate();
            }
        }

        /** {@inheritDoc} */
        @Override public double getCost(Session ses, int[] masks, TableFilter[] filters, int filter,
            SortOrder sortOrder) {
            long rows = getRowCountApproximation();
            double baseCost = getCostRangeIndex(masks, rows, filters, filter, sortOrder, true);
            int mul = delegate().getDistributedMultiplier(ses, filters, filter);

            return mul * baseCost;
        }

        /** {@inheritDoc} */
        @Override public String getPlanSQL() {
            return delegate().getTable().getSQL() + "." + SCAN_INDEX_NAME_SUFFIX;
        }

        /** {@inheritDoc} */
        @Override public String getName() {
            return delegate().getName() + SCAN_INDEX_NAME_SUFFIX;
        }
    }
}