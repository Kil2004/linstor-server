package com.linbit.linstor.core.devmgr;

import com.linbit.ImplementationError;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.Snapshot;
import com.linbit.linstor.annotation.DeviceManagerContext;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.core.devmgr.helper.LayeredResourcesHelper;
import com.linbit.linstor.core.devmgr.helper.LayeredSnapshotHelper;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.LayerFactory;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.layer.DeviceLayer.NotificationListener;
import com.linbit.linstor.storage.layer.ResourceLayer;
import com.linbit.linstor.storage.layer.exceptions.ResourceException;
import com.linbit.linstor.storage.layer.exceptions.VolumeException;
import com.linbit.utils.RemoveAfterDevMgrRework;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Singleton
public class DeviceHandlerImpl implements DeviceHandler2
{
    private final AccessContext wrkCtx;
    private final ErrorReporter errorReporter;
    private final Provider<NotificationListener> notificationListener;

    private final LayeredResourcesHelper layeredRscHelper;
    private final LayeredSnapshotHelper layeredSnapshotHelper;
    private final LayerFactory layerFactory;
    private final AtomicBoolean fullSyncApplied;

    @Inject
    public DeviceHandlerImpl(
        @DeviceManagerContext AccessContext wrkCtxRef,
        ErrorReporter errorReporterRef,
        Provider<NotificationListener> notificationListenerRef,
        LayeredResourcesHelper layeredRscHelperRef,
        LayeredSnapshotHelper layeredSnapshotHelperRef,
        LayerFactory layerFactoryRef
    )
    {
        wrkCtx = wrkCtxRef;
        errorReporter = errorReporterRef;
        notificationListener = notificationListenerRef;

        layeredRscHelper = layeredRscHelperRef;
        layeredSnapshotHelper = layeredSnapshotHelperRef;
        layerFactory = layerFactoryRef;

        fullSyncApplied = new AtomicBoolean(false);
    }

    @Override
    public void dispatchResources(Collection<Resource> rscs, Collection<Snapshot> snapshots)
    {
        Collection<Resource> origResources = rscs;
        List<Resource> allResources = convertResources(origResources);
        updateSnapshotLayerData(origResources, snapshots);

        Set<Resource> rootResources = origResources.stream().map(rsc -> getRoot(rsc)).collect(Collectors.toSet());
        Map<ResourceLayer, List<Resource>> rscByLayer = allResources.stream()
            .collect(
                Collectors.groupingBy(
                    rsc -> layerFactory.getDeviceLayer(rsc.getType().getDevLayerKind().getClass())
                )
            );
        Map<ResourceName, List<Snapshot>> snapshotsByRscName =
            snapshots.stream().collect(Collectors.groupingBy(Snapshot::getResourceName));

        // call prepare for every necessary layer
        boolean prepareSuccess = true;
        for (Entry<ResourceLayer, List<Resource>> entry : rscByLayer.entrySet())
        {
            List<Snapshot> affectedSnapshots = new ArrayList<>();
            List<Resource> rscList = entry.getValue();
            for (Resource rsc : rscList)
            {
                List<Snapshot> list = snapshotsByRscName.get(rsc.getDefinition().getName());
                if (list != null)
                {
                    affectedSnapshots.addAll(list);
                }
            }
            ResourceLayer layer = entry.getKey();
            if (!prepare(layer, rscList, affectedSnapshots))
            {
                prepareSuccess = false;
                break;
            }
        }

        if (prepareSuccess)
        {
            // actually process every resource and snapshots
            for (Resource rsc : rootResources)
            {
                ResourceName rscName = rsc.getDefinition().getName();
                ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
                try
                {
                    List<Snapshot> snapshotList = snapshotsByRscName.get(rscName);
                    if (snapshotList == null)
                    {
                        snapshotList = Collections.emptyList();
                    }
                    process(
                        rsc,
                        snapshotList,
                        apiCallRc
                    );
                }
                catch (AccessDeniedException | SQLException exc)
                {
                    throw new ImplementationError(exc);
                }
                catch (StorageException | ResourceException | VolumeException exc)
                {
                    // TODO different handling for different exceptions?
                    errorReporter.reportError(exc);

                    apiCallRc = ApiCallRcImpl.singletonApiCallRc(
                        ApiCallRcImpl.entryBuilder(
                            // TODO maybe include a ret-code into the exception
                            ApiConsts.FAIL_UNKNOWN_ERROR,
                            "An error occured while processing resource '" + rsc + "'"
                        )
                        .setCause(exc.getCauseText())
                        .setCorrection(exc.getCorrectionText())
                        .setDetails(exc.getDetailsText())
                        .build()
                    );
                }
                notificationListener.get().notifyResourceDispatchResponse(rscName, apiCallRc);
            }

            // call clear cache for every layer where the .prepare was called
            for (Entry<ResourceLayer, List<com.linbit.linstor.Resource>> entry : rscByLayer.entrySet())
            {
                ResourceLayer layer = entry.getKey();
                 try
                 {
                     layer.clearCache();
                 }
                 catch (StorageException exc)
                 {
                     errorReporter.reportError(exc);
                     ApiCallRcImpl apiCallRc = ApiCallRcImpl.singletonApiCallRc(
                         ApiCallRcImpl.entryBuilder(
                             ApiConsts.FAIL_UNKNOWN_ERROR,
                             "An error occured while cleaning up layer '" + layer.getName() + "'"
                         )
                         .setCause(exc.getCauseText())
                         .setCorrection(exc.getCorrectionText())
                         .setDetails(exc.getDetailsText())
                         .build()
                     );
                     for (Resource rsc : entry.getValue())
                     {
                         notificationListener.get().notifyResourceDispatchResponse(
                             rsc.getDefinition().getName(),
                             apiCallRc
                         );
                     }
                 }
            }

            layeredRscHelper.cleanupResources(origResources);
        }
    }

    private Resource getRoot(Resource rsc)
    {
        Resource root = rsc;
        try
        {
            Resource tmp = rsc.getParentResource(wrkCtx);
            while (tmp != null)
            {
                root = tmp;
                tmp = tmp.getParentResource(wrkCtx);
            }
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return root;
    }

    private boolean prepare(ResourceLayer layer, List<Resource> resources, List<Snapshot> affectedSnapshots)
    {
        boolean success;
        try
        {
            layer.prepare(resources, affectedSnapshots);
            success = true;
        }
        catch (StorageException exc)
        {
            success = false;
            errorReporter.reportError(exc);

            ApiCallRcImpl apiCallRc = ApiCallRcImpl.singletonApiCallRc(
                ApiCallRcImpl.entryBuilder(
                    // TODO maybe include a ret-code into the StorageException
                    ApiConsts.FAIL_UNKNOWN_ERROR,
                    "Preparing resources for layer " + layer.getName() + " failed"
                )
                .setCause(exc.getCauseText())
                .setCorrection(exc.getCorrectionText())
                .setDetails(exc.getDetailsText())
                .build()
            );
            for (Resource failedResource : resources)
            {
                notificationListener.get().notifyResourceDispatchResponse(
                    failedResource.getDefinition().getName(),
                    apiCallRc
                );
            }
        }
        catch (AccessDeniedException | SQLException exc)
        {
            throw new ImplementationError(exc);
        }
        return success;
    }

    @Override
    public void process(Resource rsc, Collection<Snapshot> snapshots, ApiCallRcImpl apiCallRc)
        throws StorageException, ResourceException, VolumeException,
            AccessDeniedException, SQLException
    {
        layerFactory
            .getDeviceLayer(
                rsc.getType().getDevLayerKind().getClass()
            )
            .process(rsc, snapshots, apiCallRc);
    }

    // TODO: create delete volume / resource mehtods that (for now) only perform the actual .delete()
    // command. This method should be a central point for future logging or other extensions / purposes

    /**
     * This method splits one {@link Resource} into device-layer-specific resources.
     * In future versions of LINSTOR this method should get obsolete as the API layer should
     * already receive the correct resources.
     * @throws AccessDeniedException
     */
    @RemoveAfterDevMgrRework
    private List<Resource> convertResources(Collection<Resource> resourcesToProcess)
    {
        // convert resourceNames to resources
        return layeredRscHelper.extractLayers(resourcesToProcess);
    }

    @RemoveAfterDevMgrRework
    private void updateSnapshotLayerData(Collection<Resource> dfltResources, Collection<Snapshot> snapshots)
    {
        layeredSnapshotHelper.updateSnapshotLayerData(dfltResources, snapshots);
    }

    public void fullSyncApplied()
    {
        fullSyncApplied.set(true);
    }
}