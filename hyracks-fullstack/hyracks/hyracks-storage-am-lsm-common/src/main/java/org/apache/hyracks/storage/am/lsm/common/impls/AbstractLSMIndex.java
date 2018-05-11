/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hyracks.storage.am.lsm.common.impls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
//import java.util.logging.Level;

import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.io.IIOManager;
import org.apache.hyracks.api.replication.IReplicationJob.ReplicationExecutionType;
import org.apache.hyracks.api.replication.IReplicationJob.ReplicationOperation;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.storage.am.common.impls.AbstractSearchPredicate;
import org.apache.hyracks.storage.am.common.impls.NoOpIndexAccessParameters;
import org.apache.hyracks.storage.am.common.ophelpers.IndexOperation;
import org.apache.hyracks.storage.am.lsm.common.api.*;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponentId.IdCompareResult;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIOOperation.LSMIOOperationType;
import org.apache.hyracks.storage.common.IIndexAccessParameters;
import org.apache.hyracks.storage.common.IIndexBulkLoader;
import org.apache.hyracks.storage.common.IIndexCursor;
import org.apache.hyracks.storage.common.buffercache.IBufferCache;
import org.apache.hyracks.util.trace.ITracer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractLSMIndex implements ILSMIndex {
    private static final Logger LOGGER = LogManager.getLogger();
    protected final ILSMHarness lsmHarness;
    protected final IIOManager ioManager;
    protected final ILSMIOOperationScheduler ioScheduler;
    protected final ILSMIOOperationCallback ioOpCallback;

    // In-memory components.
    protected final List<ILSMMemoryComponent> memoryComponents;
    protected final List<IVirtualBufferCache> virtualBufferCaches;
    protected AtomicInteger currentMutableComponentId;
    // On-disk components.
    protected final IBufferCache diskBufferCache;
    protected final ILSMIndexFileManager fileManager;
    // components with lower indexes are newer than components with higher index
    protected final List<ILSMDiskComponent> diskComponents;
    protected final List<ILSMDiskComponent> inactiveDiskComponents;

    //Specialized level based organization of components for LeveledPartitionMergePolicy
    protected final List<List<ILSMDiskComponent>> diskComponentsInLevels;

    //protected final List<ILSMDiskComponent> inactiveDiskComponents;

    protected final double bloomFilterFalsePositiveRate;
    protected final IComponentFilterHelper filterHelper;
    protected final ILSMComponentFilterFrameFactory filterFrameFactory;
    protected final LSMComponentFilterManager filterManager;
    protected final int[] treeFields;
    protected final int[] filterFields;
    protected final boolean durable;
    protected boolean isActive;
    protected final AtomicBoolean[] flushRequests;
    protected volatile boolean memoryComponentsAllocated = false;
    protected ITracer tracer;
    // Factory for creating on-disk index components during flush and merge.
    protected final ILSMDiskComponentFactory componentFactory;
    // Factory for creating on-disk index components during bulkload.
    protected final ILSMDiskComponentFactory bulkLoadComponentFactory;
    //Whole ranges MBRs of different levels in the index
    protected final List<Rectangle> rangesOflevelsAsMBRorLine;

    public AbstractLSMIndex(IIOManager ioManager, List<IVirtualBufferCache> virtualBufferCaches,
            IBufferCache diskBufferCache, ILSMIndexFileManager fileManager, double bloomFilterFalsePositiveRate,
            ILSMMergePolicy mergePolicy, ILSMOperationTracker opTracker, ILSMIOOperationScheduler ioScheduler,
            ILSMIOOperationCallbackFactory ioOpCallbackFactory, ILSMDiskComponentFactory componentFactory,
            ILSMDiskComponentFactory bulkLoadComponentFactory, ILSMComponentFilterFrameFactory filterFrameFactory,
            LSMComponentFilterManager filterManager, int[] filterFields, boolean durable,
            IComponentFilterHelper filterHelper, int[] treeFields, ITracer tracer) throws HyracksDataException {
        this.ioManager = ioManager;
        this.virtualBufferCaches = virtualBufferCaches;
        this.diskBufferCache = diskBufferCache;
        this.fileManager = fileManager;
        this.bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate;
        this.ioScheduler = ioScheduler;
        this.ioOpCallback = ioOpCallbackFactory.createIoOpCallback(this);
        this.componentFactory = componentFactory;
        this.bulkLoadComponentFactory = bulkLoadComponentFactory;
        this.filterHelper = filterHelper;
        this.filterFrameFactory = filterFrameFactory;
        this.filterManager = filterManager;
        this.treeFields = treeFields;
        this.filterFields = filterFields;
        this.inactiveDiskComponents = new LinkedList<>();
        this.durable = durable;
        this.tracer = tracer;

        isActive = false;
        diskComponents = new ArrayList<>();
        memoryComponents = new ArrayList<>();
        currentMutableComponentId = new AtomicInteger();
        flushRequests = new AtomicBoolean[virtualBufferCaches.size()];
        for (int i = 0; i < virtualBufferCaches.size(); i++) {
            flushRequests[i] = new AtomicBoolean();
        }


        if (mergePolicy.getClass().equals(LeveledParitioningMergePolicy.class)) {
            diskComponentsInLevels = new ArrayList<>();

            lsmHarness = new LeveledLSMHarness(this, mergePolicy, opTracker, diskBufferCache.isReplicationEnabled(), tracer);
            int l = ((LeveledParitioningMergePolicy) mergePolicy).getMaxLevel();
            rangesOflevelsAsMBRorLine = new ArrayList<>();
            for (int i = 0; i <= l; i++) {
                diskComponentsInLevels.add(new ArrayList<>());
                rangesOflevelsAsMBRorLine.add(new Rectangle());
            }


        }
        else
        {
            lsmHarness = new LSMHarness(this, mergePolicy, opTracker, diskBufferCache.isReplicationEnabled(), tracer);
            diskComponentsInLevels = null;
            rangesOflevelsAsMBRorLine = null;
        }
    }

    // The constructor used by external indexes
    public AbstractLSMIndex(IIOManager ioManager, IBufferCache diskBufferCache, ILSMIndexFileManager fileManager,
            double bloomFilterFalsePositiveRate, ILSMMergePolicy mergePolicy, ILSMOperationTracker opTracker,
            ILSMIOOperationScheduler ioScheduler, ILSMIOOperationCallbackFactory ioOpCallbackFactory,
            ILSMDiskComponentFactory componentFactory, ILSMDiskComponentFactory bulkLoadComponentFactory,
            boolean durable, ITracer tracer) throws HyracksDataException {
        this.ioManager = ioManager;
        this.diskBufferCache = diskBufferCache;
        this.fileManager = fileManager;
        this.bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate;
        this.ioScheduler = ioScheduler;
        this.ioOpCallback = ioOpCallbackFactory.createIoOpCallback(this);
        this.componentFactory = componentFactory;
        this.bulkLoadComponentFactory = bulkLoadComponentFactory;
        this.durable = durable;
        this.tracer = tracer;
        lsmHarness = new ExternalIndexHarness(this, mergePolicy, opTracker, diskBufferCache.isReplicationEnabled());
        isActive = false;
        diskComponents = new LinkedList<>();
        this.inactiveDiskComponents = new LinkedList<>();
        // Memory related objects are nulled
        virtualBufferCaches = null;
        memoryComponents = null;
        currentMutableComponentId = null;
        flushRequests = null;
        filterHelper = null;
        filterFrameFactory = null;
        filterManager = null;
        treeFields = null;
        filterFields = null;

        diskComponentsInLevels = null; //new LinkedList<>();
        rangesOflevelsAsMBRorLine = null;
        //To Do. Add diskComponentsInLevels code for external indexes
//        if (lsmHarness.getMergePolicy().getClass().equals(LeveledParitioningMergePolicy.class)) {
//            int l = ((LeveledParitioningMergePolicy) lsmHarness.getMergePolicy()).getMaxLevel();
//
//            for (int i = 0; i < l; i++) {
//                diskComponentsInLevels.add(new ArrayList<>());
//            }
//        }
    }

    public void clearDiskComponentsInLevels()
    {
        if(diskComponentsInLevels!=null)
        {
            int l = ((LeveledParitioningMergePolicy) lsmHarness.getMergePolicy()).getMaxLevel();

            for (int i = 0; i <= l; i++) {
                diskComponentsInLevels.get(i).clear();
            }

            //rangesOflevelsAsMBRorLine.clear();
        }
    }
    @Override
    public synchronized void create() throws HyracksDataException {
        if (isActive) {
            throw HyracksDataException.create(ErrorCode.CANNOT_CREATE_ACTIVE_INDEX);
        }
        fileManager.createDirs();
        diskComponents.clear();
        clearDiskComponentsInLevels();
    }

    @Override
    public synchronized void activate() throws HyracksDataException {
        if (isActive) {
            throw HyracksDataException.create(ErrorCode.CANNOT_ACTIVATE_ACTIVE_INDEX);
        }
        loadDiskComponents();
        isActive = true;
    }

    private void loadDiskComponents() throws HyracksDataException {
        diskComponents.clear();
        clearDiskComponentsInLevels();

        List<LSMComponentFileReferences> validFileReferences = fileManager.cleanupAndGetValidFiles();
        for (LSMComponentFileReferences lsmComponentFileReferences : validFileReferences) {
            ILSMDiskComponent component =
                    createDiskComponent(componentFactory, lsmComponentFileReferences.getInsertIndexFileReference(),
                            lsmComponentFileReferences.getDeleteIndexFileReference(),
                            lsmComponentFileReferences.getBloomFilterFileReference(), false);
            diskComponents.add(component);
            //Also update leveled index of components
            if(diskComponentsInLevels!=null)
            {
                int level = component.getLevel();
                diskComponentsInLevels.get(level).add(component);
            }

            for(int i=1; i<rangesOflevelsAsMBRorLine.size();i++)
            {
                computeRangesOfLevel(i);
            }
        }
    }

    @Override
    public List<Rectangle> getRangesOflevelsAsMBRorLine() {
        return rangesOflevelsAsMBRorLine;
    }

    @Override
    public final synchronized void deactivate() throws HyracksDataException {
        deactivate(true);
    }

    @Override
    public synchronized void deactivate(boolean flush) throws HyracksDataException {
        if (!isActive) {
            throw HyracksDataException.create(ErrorCode.CANNOT_DEACTIVATE_INACTIVE_INDEX);
        }
        if (flush) {
            flushMemoryComponent();
        }
        deactivateDiskComponents();
        deallocateMemoryComponents();
        isActive = false;
    }

    private void flushMemoryComponent() throws HyracksDataException {
        BlockingIOOperationCallbackWrapper cb = new BlockingIOOperationCallbackWrapper(ioOpCallback);
        ILSMIndexAccessor accessor = createAccessor(NoOpIndexAccessParameters.INSTANCE);
        accessor.scheduleFlush(cb);
        try {
            cb.waitForIO();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HyracksDataException.create(e);
        }
    }

    private void deactivateDiskComponents() throws HyracksDataException {
        for (ILSMDiskComponent c : diskComponents) {
            c.deactivateAndPurge();
        }
    }

    private void deallocateMemoryComponents() throws HyracksDataException {
        if (memoryComponentsAllocated) {
            for (ILSMMemoryComponent c : memoryComponents) {
                c.deallocate();
            }
            memoryComponentsAllocated = false;
        }
    }

    @Override
    public synchronized void destroy() throws HyracksDataException {
        if (isActive) {
            throw HyracksDataException.create(ErrorCode.CANNOT_DESTROY_ACTIVE_INDEX);
        }
        destroyDiskComponents();
        fileManager.deleteDirs();
    }

    private void destroyDiskComponents() throws HyracksDataException {
        for (ILSMDiskComponent c : diskComponents) {
            c.destroy();
        }
    }

    @Override
    public synchronized void clear() throws HyracksDataException {
        if (!isActive) {
            throw HyracksDataException.create(ErrorCode.CANNOT_CLEAR_INACTIVE_INDEX);
        }
        resetMemoryComponents();
        deactivateAndDestroyDiskComponents();
    }

    private void deactivateAndDestroyDiskComponents() throws HyracksDataException {
        for (ILSMDiskComponent c : diskComponents) {
            c.deactivateAndDestroy();
        }
        diskComponents.clear();
        clearDiskComponentsInLevels();
    }

    private void resetMemoryComponents() throws HyracksDataException {
        if (memoryComponentsAllocated && memoryComponents != null) {
            for (ILSMMemoryComponent c : memoryComponents) {
                c.reset();
            }
        }
    }

    @Override
    public void purge() throws HyracksDataException {
    }

    @Override
    public void getOperationalComponents(ILSMIndexOperationContext ctx) throws HyracksDataException {
        List<ILSMComponent> operationalComponents = ctx.getComponentHolder();
        int cmc = currentMutableComponentId.get();
        ctx.setCurrentMutableComponentId(cmc);
        operationalComponents.clear();
        switch (ctx.getOperation()) {
            case UPDATE:
            case PHYSICALDELETE:
            case FLUSH:
            case DELETE_MEMORY_COMPONENT:
            case DELETE:
            case UPSERT:
                operationalComponents.add(memoryComponents.get(cmc));
                break;
            case INSERT:
                addOperationalMutableComponents(operationalComponents, true);
                operationalComponents.addAll(diskComponents);
                break;
            case SEARCH:
                if (memoryComponentsAllocated) {
                    addOperationalMutableComponents(operationalComponents, false);
                }
                if (filterManager != null) {
                    for (int i = 0; i < diskComponents.size(); i++) {
                        ILSMComponent c = diskComponents.get(i);
                        if (c.getLSMComponentFilter().satisfy(
                                ((AbstractSearchPredicate) ctx.getSearchPredicate()).getMinFilterTuple(),
                                ((AbstractSearchPredicate) ctx.getSearchPredicate()).getMaxFilterTuple(),
                                ctx.getFilterCmp())) {
                            operationalComponents.add(c);
                        }
                    }
                } else {
                    operationalComponents.addAll(diskComponents);
                }

                break;
            case MERGE:
            case DELETE_DISK_COMPONENTS:
                operationalComponents.addAll(ctx.getComponentsToBeMerged());
                operationalComponents.addAll(ctx.getComponentPickedToBeMergedFromPrevLevel());
                break;
            case FULL_MERGE:
                operationalComponents.addAll(diskComponents);
                break;
            case REPLICATE:
                operationalComponents.addAll(ctx.getComponentsToBeReplicated());
                break;
            case DISK_COMPONENT_SCAN:
                operationalComponents.addAll(diskComponents);
                break;
            default:
                throw new UnsupportedOperationException("Operation " + ctx.getOperation() + " not supported.");
        }
    }

    @Override
    public void scanDiskComponents(ILSMIndexOperationContext ctx, IIndexCursor cursor) throws HyracksDataException {
        throw HyracksDataException.create(ErrorCode.DISK_COMPONENT_SCAN_NOT_ALLOWED_FOR_SECONDARY_INDEX);
    }

    @Override
    public void scheduleFlush(ILSMIndexOperationContext ctx, ILSMIOOperationCallback callback)
            throws HyracksDataException {
        LSMComponentFileReferences componentFileRefs = fileManager.getRelFlushFileReference();
        AbstractLSMIndexOperationContext opCtx = createOpContext(NoOpIndexAccessParameters.INSTANCE);
        opCtx.setOperation(ctx.getOperation());
        opCtx.getComponentHolder().addAll(ctx.getComponentHolder());
        ILSMIOOperation flushOp = createFlushOperation(opCtx, componentFileRefs, callback);
        ioScheduler.scheduleOperation(TracedIOOperation.wrap(flushOp, tracer));
    }

    @Override
    public void scheduleLeveledMerge(ILSMIndexOperationContext ctx, ILSMIOOperationCallback callback)
            throws HyracksDataException {
        List<ILSMComponent> allMergingComponents = ctx.getComponentHolder();
        // merge must create a different op ctx
        AbstractLSMIndexOperationContext opCtx = createOpContext(NoOpIndexAccessParameters.INSTANCE);
        opCtx.setOperation(ctx.getOperation());
        opCtx.getComponentHolder().addAll(allMergingComponents);
        ctx.getComponentsToBeMerged().stream().map(ILSMDiskComponent.class::cast).forEach(opCtx.getComponentsToBeMerged()::add);
        ctx.getComponentPickedToBeMergedFromPrevLevel().stream().map(ILSMDiskComponent.class::cast).forEach(opCtx.getComponentPickedToBeMergedFromPrevLevel()::add);

        //ILSMDiskComponent firstComponent = (ILSMDiskComponent) allMergingComponents.get(0);
        //ILSMDiskComponent lastComponent = (ILSMDiskComponent) allMergingComponents.get(allMergingComponents.size() - 1);

        LSMComponentFileReferences[] mergeFileRefs = getLeveledMergeFileReferences( opCtx.getComponentsToBeMerged(), opCtx.getComponentPickedToBeMergedFromPrevLevel());
        ILSMIOOperation mergeOp = createLeveledMergeOperation(opCtx, mergeFileRefs, callback);
        ioScheduler.scheduleOperation(TracedIOOperation.wrap(mergeOp, tracer));
    }

    @Override public void scheduleMerge(ILSMIndexOperationContext ctx, ILSMIOOperationCallback callback)
            throws HyracksDataException {

        List<ILSMComponent> mergingComponents = ctx.getComponentHolder();
        // merge must create a different op ctx
        AbstractLSMIndexOperationContext opCtx = createOpContext(NoOpIndexAccessParameters.INSTANCE);
        opCtx.setOperation(ctx.getOperation());
        opCtx.getComponentHolder().addAll(mergingComponents);
        mergingComponents.stream().map(ILSMDiskComponent.class::cast).forEach(opCtx.getComponentsToBeMerged()::add);
        ILSMDiskComponent firstComponent = (ILSMDiskComponent) mergingComponents.get(0);
        ILSMDiskComponent lastComponent = (ILSMDiskComponent) mergingComponents.get(mergingComponents.size() - 1);
        LSMComponentFileReferences mergeFileRefs = getMergeFileReferences(firstComponent, lastComponent);
        ILSMIOOperation mergeOp = createMergeOperation(opCtx, mergeFileRefs, callback);
        ioScheduler.scheduleOperation(TracedIOOperation.wrap(mergeOp, tracer));
    }

    private void addOperationalMutableComponents(List<ILSMComponent> operationalComponents, boolean modification) {
        int cmc = currentMutableComponentId.get();
        int numMutableComponents = memoryComponents.size();
        for (int i = 0; i < numMutableComponents - 1; i++) {
            ILSMMemoryComponent c = memoryComponents.get((cmc + i + 1) % numMutableComponents);
            if (c.isReadable()) {
                // Make sure newest components are added first if readable
                operationalComponents.add(0, c);
            }
        }
        // The current mutable component is added if modification operation or if readable
        // This ensures that activation of new component only happens in case of modifications
        // and allow for controlling that without stopping search operations
        ILSMMemoryComponent c = memoryComponents.get(cmc);
        if (modification || c.isReadable()) {
            operationalComponents.add(0, c);
        }
    }

    @Override
    public final IIndexBulkLoader createBulkLoader(float fillLevel, boolean verifyInput, long numElementsHint,
            boolean checkIfEmptyIndex) throws HyracksDataException {
        if (checkIfEmptyIndex && !isEmptyIndex()) {
            throw HyracksDataException.create(ErrorCode.LOAD_NON_EMPTY_INDEX);
        }
        return createBulkLoader(fillLevel, verifyInput, numElementsHint);
    }

    public IIndexBulkLoader createBulkLoader(float fillLevel, boolean verifyInput, long numElementsHint)
            throws HyracksDataException {
        AbstractLSMIndexOperationContext opCtx = createOpContext(NoOpIndexAccessParameters.INSTANCE);
        opCtx.setIoOperationType(LSMIOOperationType.LOAD);
        ioOpCallback.beforeOperation(opCtx);
        return new LSMIndexDiskComponentBulkLoader(this, opCtx, fillLevel, verifyInput, numElementsHint);
    }

    @Override
    public ILSMDiskComponent createBulkLoadTarget() throws HyracksDataException {
        LSMComponentFileReferences componentFileRefs = fileManager.getRelFlushFileReference();
        return createDiskComponent(bulkLoadComponentFactory, componentFileRefs.getInsertIndexFileReference(),
                componentFileRefs.getDeleteIndexFileReference(), componentFileRefs.getBloomFilterFileReference(), true);
    }

    protected ILSMDiskComponent createDiskComponent(ILSMDiskComponentFactory factory, FileReference insertFileReference,
            FileReference deleteIndexFileReference, FileReference bloomFilterFileRef, boolean createComponent)
            throws HyracksDataException {
        ILSMDiskComponent component = factory.createComponent(this,
                new LSMComponentFileReferences(insertFileReference, deleteIndexFileReference, bloomFilterFileRef));
        component.activate(createComponent);
        return component;
    }

    @Override
    public synchronized void allocateMemoryComponents() throws HyracksDataException {
        if (!isActive) {
            throw HyracksDataException.create(ErrorCode.CANNOT_ALLOCATE_MEMORY_FOR_INACTIVE_INDEX);
        }
        if (memoryComponentsAllocated || memoryComponents == null) {
            return;
        }
        int i = 0;
        boolean allocated = false;
        try {
            for (; i < memoryComponents.size(); i++) {
                allocated = false;
                ILSMMemoryComponent c = memoryComponents.get(i);
                c.allocate();
                allocated = true;
                ioOpCallback.allocated(c);
            }
        } finally {
            if (i < memoryComponents.size()) {
                // something went wrong
                if (allocated) {
                    ILSMMemoryComponent c = memoryComponents.get(i);
                    c.deallocate();
                }
                // deallocate all previous components
                for (int j = i - 1; j >= 0; j--) {
                    ILSMMemoryComponent c = memoryComponents.get(j);
                    c.deallocate();
                }
            }
        }
        memoryComponentsAllocated = true;
    }

    @Override
    public void addDiskComponent(ILSMDiskComponent c) throws HyracksDataException {
        if (c != EmptyComponent.INSTANCE) {
            diskComponents.add(0, c);
            //Also update leveled index of components
            if(diskComponentsInLevels!=null)
            {
                int level = c.getLevel();
                diskComponentsInLevels.get(level).add(c);
            }
        }
        assert checkComponentIds();
    }

    @Override
    public void subsumeMergedComponents(ILSMDiskComponent newComponent, List<ILSMComponent> mergedComponents)
            throws HyracksDataException {
        int swapIndex = diskComponents.indexOf(mergedComponents.get(0));
        diskComponents.removeAll(mergedComponents);
        if (newComponent != EmptyComponent.INSTANCE) {
            diskComponents.add(swapIndex, newComponent);
        }
        assert checkComponentIds();
    }

    @Override public void subsumeLeveledMergedComponents(List<ILSMDiskComponent> newComponents,
            List<ILSMComponent> mergedComponents) throws HyracksDataException {
        int swapIndex = diskComponents.indexOf(mergedComponents.get(0));
        diskComponents.removeAll(mergedComponents);

        //Also remove from Leveled index of components
        if(diskComponentsInLevels!=null) {
            for (ILSMComponent c : mergedComponents) {
                int level = ((ILSMDiskComponent) c).getLevel();
                diskComponentsInLevels.get(level).remove(c);
            }
        }
        for (ILSMDiskComponent newComponent : newComponents) {
            if (newComponent != EmptyComponent.INSTANCE)
            {
                int level = newComponent.getLevel();
                diskComponentsInLevels.get(level).add(newComponent);
                diskComponents.add(newComponent);
            }
        }
        assert checkComponentIds();
    }

    /**
     * A helper method to ensure disk components have proper Ids (non-decreasing)
     * We may get rid of this method once component Id is stablized
     *
     * @throws HyracksDataException
     */
    private boolean checkComponentIds() throws HyracksDataException {
        for (int i = 0; i < diskComponents.size() - 1; i++) {
            ILSMComponentId id1 = diskComponents.get(i).getId();
            ILSMComponentId id2 = diskComponents.get(i + 1).getId();
            IdCompareResult cmp = id1.compareTo(id2);
            if (cmp != IdCompareResult.UNKNOWN && cmp != IdCompareResult.GREATER_THAN) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void changeMutableComponent() {
        currentMutableComponentId.set((currentMutableComponentId.get() + 1) % memoryComponents.size());
        memoryComponents.get(currentMutableComponentId.get()).requestActivation();
    }

    @Override
    public List<ILSMDiskComponent> getDiskComponents() {
        return diskComponents;
    }

    @Override
    public List<List<ILSMDiskComponent>> getDiskComponentsInLevels() {
        return diskComponentsInLevels;
    }

    @Override
    public void changeFlushStatusForCurrentMutableCompoent(boolean needsFlush) {
        flushRequests[currentMutableComponentId.get()].set(needsFlush);
    }

    @Override
    public boolean hasFlushRequestForCurrentMutableComponent() {
        return flushRequests[currentMutableComponentId.get()].get();
    }

    @Override
    public ILSMOperationTracker getOperationTracker() {
        return lsmHarness.getOperationTracker();
    }

    @Override
    public ILSMIOOperationScheduler getIOScheduler() {
        return ioScheduler;
    }

    @Override
    public ILSMIOOperationCallback getIOOperationCallback() {
        return ioOpCallback;
    }

    @Override
    public IBufferCache getBufferCache() {
        return diskBufferCache;
    }

    public boolean isEmptyIndex() {
        boolean isModified = false;
        for (ILSMComponent c : memoryComponents) {
            AbstractLSMMemoryComponent mutableComponent = (AbstractLSMMemoryComponent) c;
            if (mutableComponent.isModified()) {
                isModified = true;
                break;
            }
        }
        return diskComponents.isEmpty() && !isModified;
    }

    @Override
    public final String toString() {
        return "{\"class\" : \"" + getClass().getSimpleName() + "\", \"dir\" : \"" + fileManager.getBaseDir()
                + "\", \"memory\" : " + (memoryComponents == null ? 0 : memoryComponents.size()) + ", \"disk\" : "
                + diskComponents.size() + "}";
    }

    @Override
    public final int getNumberOfAllMemoryComponents() {
        return virtualBufferCaches == null ? 0 : virtualBufferCaches.size();
    }

    @Override
    public boolean isCurrentMutableComponentEmpty() throws HyracksDataException {
        //check if the current memory component has been modified
        return !memoryComponents.get(currentMutableComponentId.get()).isModified();
    }

    @Override
    public List<ILSMDiskComponent> getInactiveDiskComponents() {
        return inactiveDiskComponents;
    }

    @Override
    public void addInactiveDiskComponent(ILSMDiskComponent diskComponent) {
        inactiveDiskComponents.add(diskComponent);
    }

    @Override
    public void scheduleReplication(ILSMIndexOperationContext ctx, List<ILSMDiskComponent> lsmComponents,
            boolean bulkload, ReplicationOperation operation, LSMOperationType opType) throws HyracksDataException {
        //get set of files to be replicated for this component
        Set<String> componentFiles = new HashSet<>();

        //get set of files to be replicated for each component
        for (ILSMDiskComponent lsmComponent : lsmComponents) {
            componentFiles.addAll(lsmComponent.getLSMComponentPhysicalFiles());
        }

        ReplicationExecutionType executionType;
        if (bulkload) {
            executionType = ReplicationExecutionType.SYNC;
        } else {
            executionType = ReplicationExecutionType.ASYNC;
        }

        //create replication job and submit it
        LSMIndexReplicationJob job =
                new LSMIndexReplicationJob(this, ctx, componentFiles, operation, executionType, opType);
        try {
            diskBufferCache.getIOReplicationManager().submitJob(job);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public boolean isMemoryComponentsAllocated() {
        return memoryComponentsAllocated;
    }

    @Override
    public boolean isDurable() {
        return durable;
    }

    @Override
    public ILSMMemoryComponent getCurrentMemoryComponent() {
        return memoryComponents.get(currentMutableComponentId.get());
    }

    @Override
    public int getCurrentMemoryComponentIndex() {
        return currentMutableComponentId.get();
    }

    @Override
    public List<ILSMMemoryComponent> getMemoryComponents() {
        return memoryComponents;
    }

    protected IBinaryComparatorFactory[] getFilterCmpFactories() {
        return filterHelper == null ? null : filterHelper.getFilterCmpFactories();
    }

    @Override
    public int getNumOfFilterFields() {
        return filterFields == null ? 0 : filterFields.length;
    }

    public double bloomFilterFalsePositiveRate() {
        return bloomFilterFalsePositiveRate;
    }

    @Override
    public void updateFilter(ILSMIndexOperationContext ctx, ITupleReference tuple) throws HyracksDataException {
        if (ctx.getFilterTuple() != null && !ctx.isFilterSkipped()) {
            if (ctx.isRecovery()) {
                memoryComponents.get(currentMutableComponentId.get()).getLSMComponentFilter().update(tuple,
                        ctx.getFilterCmp(), ctx.getModificationCallback());
            } else {
                ctx.getFilterTuple().reset(tuple);
                memoryComponents.get(currentMutableComponentId.get()).getLSMComponentFilter()
                        .update(ctx.getFilterTuple(), ctx.getFilterCmp(), ctx.getModificationCallback());
            }
        }
    }

    public int[] getFilterFields() {
        return filterFields;
    }

    public int[] getTreeFields() {
        return treeFields;
    }

    public LSMComponentFilterManager getFilterManager() {
        return filterManager;
    }

    @Override
    public ILSMHarness getHarness() {
        return lsmHarness;
    }

    @Override
    public final void validate() throws HyracksDataException {
        if (memoryComponentsAllocated) {
            for (ILSMMemoryComponent c : memoryComponents) {
                c.validate();
            }
        }
        for (ILSMDiskComponent c : diskComponents) {
            c.validate();
        }
    }

    @Override
    public long getMemoryAllocationSize() {
        long size = 0;
        for (ILSMMemoryComponent c : memoryComponents) {
            size += c.getSize();
        }
        return size;
    }

    @Override
    public final ILSMDiskComponent flush(ILSMIOOperation operation) throws HyracksDataException {
        ILSMIndexAccessor accessor = operation.getAccessor();
        ILSMIndexOperationContext opCtx = accessor.getOpContext();
        if (opCtx.getOperation() == IndexOperation.DELETE_MEMORY_COMPONENT) {
            return EmptyComponent.INSTANCE;
        }
        if (LOGGER.isInfoEnabled()) {
            FlushOperation flushOp = (FlushOperation) operation;
            LOGGER.log(Level.INFO, "Flushing component with id: " + flushOp.getFlushingComponent().getId());
        }
        try {
            return doFlush(operation);
        } catch (Exception e) {
            LOGGER.error("Fail to execute flush " + this, e);
            cleanUpFiles(operation, e);
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public final ILSMDiskComponent merge(ILSMIOOperation operation) throws HyracksDataException {
        ILSMIndexAccessor accessor = operation.getAccessor();
        ILSMIndexOperationContext opCtx = accessor.getOpContext();
        try {
            return opCtx.getOperation() == IndexOperation.DELETE_DISK_COMPONENTS ? EmptyComponent.INSTANCE
                    : doMerge(operation);
        } catch (Exception e) {
            LOGGER.error("Fail to execute merge " + this, e);
            cleanUpFiles(operation, e);
            throw HyracksDataException.create(e);
        }
    }

    @Override public List<ILSMDiskComponent> leveledMerge(ILSMIOOperation operation) throws HyracksDataException {
        ILSMIndexAccessor accessor = operation.getAccessor();
        ILSMIndexOperationContext opCtx = accessor.getOpContext();
        try {
            return opCtx.getOperation() == IndexOperation.MERGE ? doLeveledMerge(operation): null;
        } catch (Exception e) {
            LOGGER.error("Fail to execute merge " + this, e);
            cleanUpLeveledFiles(operation, e);
            throw HyracksDataException.create(e);
        }
    }

    protected void cleanUpFiles(ILSMIOOperation operation, Exception e) {
        LSMComponentFileReferences componentFiles = operation.getComponentFiles();
        if (componentFiles == null) {
            return;
        }
        FileReference[] files = componentFiles.getFileReferences();
        for (FileReference file : files) {
            try {
                if (file != null) {
                    diskBufferCache.deleteFile(file);
                }
            } catch (HyracksDataException hde) {
                e.addSuppressed(hde);
            }
        }
    }
    protected void cleanUpLeveledFiles(ILSMIOOperation operation, Exception e) {
        List<LSMComponentFileReferences> leveledComponentFiles = operation.getLeveledMergeComponentFiles();
        if (leveledComponentFiles == null) {
            return;
        }
        for(int i=0; i < leveledComponentFiles.size(); i++) {

            LSMComponentFileReferences componentFiles = leveledComponentFiles.get(i);
            FileReference[] files = componentFiles.getFileReferences();
            for (FileReference file : files) {
                try {
                    if (file != null) {
                        diskBufferCache.deleteFile(file);
                    }
                } catch (HyracksDataException hde) {
                    e.addSuppressed(hde);
                }
            }
        }
    }
    protected abstract LSMComponentFileReferences getMergeFileReferences(ILSMDiskComponent firstComponent,
            ILSMDiskComponent lastComponent) throws HyracksDataException;

    protected abstract LSMComponentFileReferences[] getLeveledMergeFileReferences(List<ILSMDiskComponent> mergingComponentsFromNextLevel, List<ILSMDiskComponent> mergingComponentsFromprevLevel) throws HyracksDataException;

    protected abstract AbstractLSMIndexOperationContext createOpContext(IIndexAccessParameters iap)
            throws HyracksDataException;

    protected abstract ILSMIOOperation createFlushOperation(AbstractLSMIndexOperationContext opCtx,
            LSMComponentFileReferences componentFileRefs, ILSMIOOperationCallback callback) throws HyracksDataException;

    protected abstract ILSMIOOperation createMergeOperation(AbstractLSMIndexOperationContext opCtx,
            LSMComponentFileReferences mergeFileRefs, ILSMIOOperationCallback callback) throws HyracksDataException;

    protected abstract ILSMIOOperation createLeveledMergeOperation(AbstractLSMIndexOperationContext opCtx,
            LSMComponentFileReferences[] mergeFileRefs, ILSMIOOperationCallback callback) throws HyracksDataException;


    protected abstract ILSMDiskComponent doFlush(ILSMIOOperation operation) throws HyracksDataException;

    protected abstract ILSMDiskComponent doMerge(ILSMIOOperation operation) throws HyracksDataException;

    protected abstract List<ILSMDiskComponent> doLeveledMerge(ILSMIOOperation operation) throws HyracksDataException;

    protected abstract void computeRangesOfLevel(int level) throws HyracksDataException;
}
