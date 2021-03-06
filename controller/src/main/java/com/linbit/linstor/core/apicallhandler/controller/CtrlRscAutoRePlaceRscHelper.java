package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.ImplementationError;
import com.linbit.linstor.PriorityProps;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.interfaces.AutoSelectFilterApi;
import com.linbit.linstor.api.pojo.AutoSelectFilterPojo;
import com.linbit.linstor.core.apicallhandler.ScopeRunner;
import com.linbit.linstor.core.apicallhandler.controller.CtrlRscAutoHelper.AutoHelper;
import com.linbit.linstor.core.apicallhandler.controller.CtrlRscAutoHelper.AutoHelperContext;
import com.linbit.linstor.core.apicallhandler.controller.autoplacer.Autoplacer;
import com.linbit.linstor.core.apicallhandler.controller.internal.CtrlSatelliteUpdateCaller;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.apicallhandler.response.ApiOperation;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.apicallhandler.response.CtrlResponseUtils;
import com.linbit.linstor.core.apicallhandler.response.ResponseContext;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.Resource.Flags;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.core.objects.VolumeDefinition;
import com.linbit.linstor.core.repository.SystemConfRepository;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.stateflags.StateFlags;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.utils.layer.LayerRscUtils;
import com.linbit.locks.LockGuardFactory;
import com.linbit.locks.LockGuardFactory.LockObj;
import com.linbit.utils.Pair;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import reactor.util.function.Tuple2;

@Singleton
public class CtrlRscAutoRePlaceRscHelper implements AutoHelper
{
    private static final String STATUS_CONNECTED = "Connected";

    private final Provider<AccessContext> accCtx;
    private final SystemConfRepository systemConfRepo;
    private final HashSet<ResourceDefinition> needRePlaceRsc = new HashSet<>();
    private final HashSet<ResourceDefinition> needDiskfulRsc = new HashSet<>();
    private final CtrlRscAutoPlaceApiCallHandler autoPlaceHandler;
    private final CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCaller;
    private final ScopeRunner scopeRunner;
    private final LockGuardFactory lockGuardFactory;
    private final CtrlTransactionHelper ctrlTransactionHelper;
    private final ErrorReporter errorReporter;

    private final Autoplacer autoplacer;

    @Inject
    public CtrlRscAutoRePlaceRscHelper(
        @PeerContext Provider<AccessContext> accCtxRef,
        SystemConfRepository systemConfRepoRef,
        CtrlRscAutoPlaceApiCallHandler autoPlaceHandlerRef,
        CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCallerRef,
        ScopeRunner scopeRunnerRef,
        LockGuardFactory lockGuardFactoryRef,
        CtrlTransactionHelper ctrlTransactionHelperRef,
        ErrorReporter errorReporterRef,
        Autoplacer autoplacerRef
    )
    {
        accCtx = accCtxRef;
        systemConfRepo = systemConfRepoRef;
        autoPlaceHandler = autoPlaceHandlerRef;
        ctrlSatelliteUpdateCaller = ctrlSatelliteUpdateCallerRef;
        scopeRunner = scopeRunnerRef;
        lockGuardFactory = lockGuardFactoryRef;
        ctrlTransactionHelper = ctrlTransactionHelperRef;
        errorReporter = errorReporterRef;
        autoplacer = autoplacerRef;
    }

    @Override
    public void manage(AutoHelperContext ctx)
    {
        ResourceDefinition rscDfn = ctx.rscDfn;
        if (needRePlaceRsc.contains(rscDfn))
        {
            if (countDiskfulRsc(rscDfn) == 0)
            {
                if (!needDiskfulRsc.contains(rscDfn))
                {
                    needDiskfulRsc.add(rscDfn);
                    errorReporter.logWarning(
                        "There are no diskful resources of resource definition %s that are connected",
                        rscDfn.getName()
                    );
                }
            }
            else
            {
                PriorityProps props;
                int minReplicaCount;
                int placeCount;
                int curReplicaCount = 0;
                try
                {
                    props = new PriorityProps(
                        rscDfn.getProps(accCtx.get()),
                        rscDfn.getResourceGroup().getProps(accCtx.get()),
                        systemConfRepo.getCtrlConfForView(accCtx.get())
                    );
                    placeCount = rscDfn.getResourceGroup().getAutoPlaceConfig().getReplicaCount(accCtx.get());
                    minReplicaCount = Integer.parseInt(
                        props.getProp(
                            ApiConsts.KEY_AUTO_EVICT_MIN_REPLICA_COUNT,
                            ApiConsts.NAMESPC_DRBD_OPTIONS,
                            "" + placeCount
                        )
                    );
                    if (placeCount < minReplicaCount) // minReplicaCount should be smaller than placeCount
                    {
                        minReplicaCount = placeCount;
                    }
                    Iterator<Resource> itres = rscDfn.iterateResource(accCtx.get());
                    while (itres.hasNext())
                    {
                        Resource res = itres.next();
                        StateFlags<Flags> flags = res.getStateFlags();
                        if (
                            !flags.isSomeSet(
                                accCtx.get(), Resource.Flags.DELETE, Resource.Flags.TIE_BREAKER,
                                Resource.Flags.DRBD_DISKLESS
                            ) &&
                                LayerRscUtils.getLayerStack(res, accCtx.get()).contains(DeviceLayerKind.DRBD) &&
                                !res.getNode().getFlags().isSet(accCtx.get(), Node.Flags.EVICTED)
                        )
                        {
                            curReplicaCount++;
                        }
                    }
                    if (curReplicaCount < minReplicaCount)
                    {
                        AutoSelectFilterApi selectFilter = AutoSelectFilterPojo.merge(
                            new AutoSelectFilterPojo(
                                minReplicaCount - curReplicaCount,
                                null,
                                null,
                                null,
                                Collections.singletonList(rscDfn.getName().displayValue),
                                null,
                                null,
                                null,
                                Collections.singletonList(DeviceLayerKind.DRBD),
                                null,
                                null,
                                null,
                                null
                            ),
                            rscDfn.getResourceGroup().getAutoPlaceConfig().getApiData()
                        );
                        try
                        {
                            Flux<ApiCallRc> flux = scopeRunner.fluxInTransactionalScope(
                                "evict resources",
                                lockGuardFactory.createDeferred().write(LockObj.RSC_DFN_MAP).build(),
                                () ->
                                {
                                    Iterator<Resource> itr = rscDfn.iterateResource(accCtx.get());
                                    List<NodeName> nodeNameOfEvictedResources = new ArrayList<>();
                                    while (itr.hasNext())
                                    {
                                        Resource rsc = itr.next();
                                        if (rsc.getNode().getFlags().isSet(accCtx.get(), Node.Flags.EVICTED))
                                        {
                                            rsc.markDeleted(accCtx.get());
                                            nodeNameOfEvictedResources.add(rsc.getNode().getName());
                                        }
                                    }
                                    ctrlTransactionHelper.commit();
                                    Flux<Tuple2<NodeName, Flux<ApiCallRc>>> updateFlux = ctrlSatelliteUpdateCaller
                                        .updateSatellites(rscDfn, ignored -> Flux.empty(), Flux.empty());
                                    return updateFlux.transform(
                                        updateResponses -> CtrlResponseUtils.combineResponses(
                                            updateResponses,
                                            rscDfn.getName(),
                                            nodeNameOfEvictedResources,
                                            "Resource {1} was evicted from {0}",
                                            "Notified {0} about evicting resource {1} from node(s) " +
                                                nodeNameOfEvictedResources
                                        )
                                    );
                                }
                            );
                            long size = getVlmSize(rscDfn);
                            errorReporter.logDebug(
                                "Auto-evict: Auto-placing '%s' on %d additional nodes",
                                rscDfn.getName(),
                                minReplicaCount - curReplicaCount
                            );
                            Optional<Set<StorPool>> bestCandidate = autoplacer.autoPlace(selectFilter, rscDfn, size);
                            if (bestCandidate.isPresent())
                            {
                                Set<StorPool> candidate = bestCandidate.get();

                                Pair<List<Flux<ApiCallRc>>, Set<Resource>> deployedResources = autoPlaceHandler
                                    .createResources(
                                        new ResponseContext(
                                            ApiOperation.makeDeleteOperation(),
                                            "Auto-evicting resource: " + rscDfn.getName(),
                                            "auto-evicting resource: " + rscDfn.getName(),
                                            ApiConsts.MASK_DEL,
                                            new HashMap<String, String>()
                                        ),
                                        new ApiCallRcImpl(),
                                        rscDfn.getName().toString(),
                                        selectFilter.getDisklessOnRemaining(),
                                        candidate,
                                        null,
                                        selectFilter.getLayerStackList()
                                    );
                                ctrlTransactionHelper.commit();
                                ctx.additionalFluxList
                                    .add(Flux.merge(deployedResources.objA)
                                        .concatWith(flux)
                                        .doOnComplete(() ->
                                        {
                                            needRePlaceRsc.remove(rscDfn);
                                            needDiskfulRsc.remove(rscDfn);
                                        })
                                    );
                                ctx.requiresUpdateFlux = true;
                            }
                            else
                            {
                                errorReporter.logWarning(
                                    "Not enough space on nodes for eviction of resource %s", rscDfn.getName()
                                );
                            }
                        }
                        catch (ApiRcException exc)
                        {
                            // Ignored, try again later
                        }
                    }
                    else
                    {
                        needRePlaceRsc.remove(rscDfn);
                        needDiskfulRsc.remove(rscDfn);
                    }
                }
                catch (AccessDeniedException exc)
                {
                    throw new ApiAccessDeniedException(
                        exc,
                        "accessing flags of " + rscDfn.getName(),
                        ApiConsts.FAIL_ACC_DENIED_RSC_DFN
                    );
                }
            }
        }
    }

    private int countDiskfulRsc(ResourceDefinition rscDfn)
    {
        try
        {
            int ct = 0;
            for (Resource rsc : rscDfn.streamResource(accCtx.get()).collect(Collectors.toList()))
            {
                if (rsc.getNode().getPeer(accCtx.get()).isConnected() &&
                    !rsc.getStateFlags().isSet(accCtx.get(), Resource.Flags.DRBD_DISKLESS))
                {
                    ct++;
                }
            }
            return ct;
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    private long getVlmSize(ResourceDefinition rscDfn) throws AccessDeniedException
    {
        long size = 0;
        Iterator<VolumeDefinition> vlmDfnIt = rscDfn.iterateVolumeDfn(accCtx.get());
        while (vlmDfnIt.hasNext())
        {
            VolumeDefinition vlmDfn = vlmDfnIt.next();
            size += vlmDfn.getVolumeSize(accCtx.get());
        }
        return size;
    }

    public void addNeedRePlaceRsc(Resource rsc)
    {
        needRePlaceRsc.add(rsc.getDefinition());
    }
}
