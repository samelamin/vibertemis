package com.vibertemis.quest.pcvr;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** DNS-SD supplies hints only. Callers must authenticate the pinned TLS endpoint. */
public final class PairedDiscovery {
    private static final String SERVICE="_vibertemis-vr._tcp.";
    public static HostPairing find(Context context, HostPairing paired, HostClient client) throws Exception {
        NsdManager manager=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        if(manager==null)throw new IOException("LAN discovery unavailable; use the saved address or VPN.");
        LinkedBlockingQueue<HostPairing> matches=new LinkedBlockingQueue<>(8);
        AtomicBoolean closed=new AtomicBoolean(),resolving=new AtomicBoolean();
        AtomicInteger attempts=new AtomicInteger();
        ConcurrentLinkedQueue<NsdServiceInfo> pending=new ConcurrentLinkedQueue<>();
        Set<String> seen=ConcurrentHashMap.newKeySet();
        Runnable resolveNext=new Runnable(){
            public void run(){
                if(closed.get() || client.isCancelled() || attempts.get()>=16 || !resolving.compareAndSet(false,true))return;
                NsdServiceInfo info=pending.poll();
                if(info==null){resolving.set(false);return;}
                attempts.incrementAndGet();
                try{manager.resolveService(info,new NsdManager.ResolveListener(){
                    private void next(){resolving.set(false);run();}
                    public void onResolveFailed(NsdServiceInfo service,int code){next();}
                    public void onServiceResolved(NsdServiceInfo service){
                        if(!closed.get()&&!client.isCancelled()){
                            try{HostPairing hint=candidate(paired,service);if(hint!=null)matches.offer(hint);}catch(Exception ignored){}
                        }
                        next();
                    }
                });}catch(RuntimeException e){resolving.set(false);run();}
            }
        };
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onServiceLost(NsdServiceInfo info){}
            public void onStartDiscoveryFailed(String type,int code){closed.set(true);}
            public void onStopDiscoveryFailed(String type,int code){}
            public void onServiceFound(NsdServiceInfo info){
                if(closed.get()||client.isCancelled()||seen.size()>=16||!seen.add(info.getServiceName()))return;
                pending.offer(info);resolveNext.run();
            }
        };
        try {
            manager.discoverServices(SERVICE,NsdManager.PROTOCOL_DNS_SD,listener);
            long end=android.os.SystemClock.elapsedRealtime()+8000;
            while(!closed.get()&&!client.isCancelled()&&android.os.SystemClock.elapsedRealtime()<end){
                HostPairing candidate=matches.poll(200,TimeUnit.MILLISECONDS);
                if(candidate!=null)return candidate;
            }
            throw new IOException(client.isCancelled()?"Cancelled":"Paired PC not discovered. Check its host manager or use your VPN/direct address.");
        } finally {
            closed.set(true);
            try{manager.stopServiceDiscovery(listener);}catch(RuntimeException ignored){}
        }
    }
    static HostPairing candidate(HostPairing paired,NsdServiceInfo service) throws Exception {
        Map<String,byte[]> txt=service.getAttributes();
        byte[] identity=txt.get("certpin"),protocol=txt.get("protocol");
        if(identity==null||protocol==null||!paired.pin.equals(new String(identity,StandardCharsets.US_ASCII))
            ||!"20.14.1-vibertemis-pyro.1".equals(new String(protocol,StandardCharsets.US_ASCII)))return null;
        if(!(service.getHost() instanceof Inet4Address)||service.getHost().isLoopbackAddress()
            ||service.getHost().isAnyLocalAddress()||service.getHost().isMulticastAddress())return null;
        return paired.withAddress(service.getHost().getHostAddress()+":"+service.getPort());
    }
}
